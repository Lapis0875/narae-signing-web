package com.naraesigning.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.naraesigning.board.api.BoardLifecycleEvent;
import com.naraesigning.signature.SignatureLimits;
import com.naraesigning.signature.SignatureSubmitted;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty("spring.datasource.url")
public final class LiveSignatureRegistry {
    private final ObjectMapper json;
    private final Clock clock;
    private final BoardRealtimeRegistry boards;
    private final PublicBoardRealtimeRegistry publicBoards;
    private final ConcurrentHashMap<UUID, Draft> drafts = new ConcurrentHashMap<>();
    private final Set<UUID> fencedBoards = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PendingUpdates> pendingUpdates = new HashMap<>();

    LiveSignatureRegistry(
            ObjectMapper json,
            Clock authClock,
            BoardRealtimeRegistry boards,
            PublicBoardRealtimeRegistry publicBoards) {
        this.json = json;
        this.clock = authClock;
        this.boards = boards;
        this.publicBoards = publicBoards;
    }

    // ponytail: one mutation lock preserves event order; use per-slot locks if signer throughput matters.
    public synchronized Version update(
            UUID boardId, UUID slotId, UUID claimId, byte[] canonicalPayload, Instant expiresAt) {
        return update(boardId, slotId, claimId, canonicalPayload, expiresAt, draftEpoch(boardId, slotId));
    }

    public synchronized FullUpdate beginFullUpdate(UUID boardId, UUID slotId) {
        if (fencedBoards.contains(boardId)) throw new OutOfSyncException();
        var pending = pendingUpdates.computeIfAbsent(boardId, ignored -> new PendingUpdates());
        pending.active++;
        return new FullUpdate(boardId, slotId, draftEpoch(boardId, slotId), pending.generation);
    }

    public synchronized Version update(
            UUID boardId,
            UUID slotId,
            UUID claimId,
            byte[] canonicalPayload,
            Instant expiresAt,
            FullUpdate fullUpdate) {
        ensureCurrent(boardId, slotId, fullUpdate);
        return update(boardId, slotId, claimId, canonicalPayload, expiresAt, fullUpdate.draftEpoch);
    }

    public synchronized Version update(
            UUID boardId,
            UUID slotId,
            UUID claimId,
            byte[] canonicalPayload,
            Instant expiresAt,
            long expectedDraftEpoch) {
        if (fencedBoards.contains(boardId)) throw new OutOfSyncException();
        try {
            var signature = (ObjectNode) json.readTree(canonicalPayload);
            var updated = drafts.compute(slotId, (id, current) -> {
                var currentEpoch = current == null || !current.boardId().equals(boardId)
                        ? 0 : current.draftEpoch();
                if (currentEpoch != expectedDraftEpoch) throw new OutOfSyncException();
                var version = new Version(currentEpoch + 1, 0);
                return new Draft(boardId, claimId, signature, true, expiresAt,
                        version.draftEpoch(), 0, 0, null, -1);
            });
            boards.publish(boardId, "signature-draft");
            publishDraft(slotId, updated, "full-reset", -1, List.of());
            return new Version(updated.draftEpoch(), updated.revision());
        } catch (IOException exception) {
            throw new IllegalStateException("Validated signature payload was not JSON", exception);
        }
    }

    public synchronized long draftEpoch(UUID boardId, UUID slotId) {
        var current = drafts.get(slotId);
        return current == null || !current.boardId().equals(boardId) ? 0 : current.draftEpoch();
    }

    public DeltaResult apply(
            UUID boardId,
            UUID slotId,
            UUID claimId,
            DraftDelta delta,
            Supplier<Instant> authorize) {
        synchronized (this) {
            if (fencedBoards.contains(boardId)) throw new OutOfSyncException();
            var current = drafts.get(slotId);
            var now = clock.instant();
            if (current != null && current.expiresAt().isAfter(now)) {
                return applyLocked(boardId, slotId, claimId, delta, null, now);
            }
        }
        try (var pendingUpdate = beginFullUpdate(boardId, slotId)) {
            var authorizedUntil = authorize.get();
            synchronized (this) {
                ensureCurrent(boardId, slotId, pendingUpdate);
                return applyLocked(boardId, slotId, claimId, delta, authorizedUntil, clock.instant());
            }
        }
    }

    private DeltaResult applyLocked(
            UUID boardId,
            UUID slotId,
            UUID claimId,
            DraftDelta delta,
            Instant authorizedUntil,
            Instant now) {
        if (fencedBoards.contains(boardId)) throw new OutOfSyncException();
        var result = new DeltaResult[1];
        var outOfSync = new boolean[1];
        drafts.compute(slotId, (id, current) -> {
            if (current == null || !current.expiresAt().isAfter(now)) {
                if (authorizedUntil == null) throw new IllegalStateException("Missing draft authorization");
                outOfSync[0] = true;
                return new Draft(boardId, claimId, emptySignature(), false, authorizedUntil,
                        current == null ? 0 : current.draftEpoch() + 1, 0, 0, null, -1);
            }
            if (!current.boardId().equals(boardId) || !claimId.equals(current.claimId())
                    || !current.visible()) {
                outOfSync[0] = true;
                return current;
            }
            if (delta.draftEpoch() == current.draftEpoch()
                    && delta.clientSequence() == current.clientSequence()
                    && delta.equals(current.lastDelta())) {
                result[0] = new DeltaResult(current.draftEpoch(), current.revision(), true);
                return current;
            }
            if (delta.draftEpoch() != current.draftEpoch()
                    || delta.revision() != current.revision()
                    || delta.clientSequence() != current.clientSequence() + 1) {
                outOfSync[0] = true;
                return current;
            }
            var applied = applyDelta(current, delta);
            result[0] = new DeltaResult(applied.draftEpoch(), applied.revision(), false);
            return applied;
        });
        if (outOfSync[0]) throw new OutOfSyncException();
        if (!result[0].duplicate()) {
            boards.publish(boardId, "signature-draft");
            publicBoards.publish(boardId, "signature-draft", new PublicDraftEvent(
                    slotId, result[0].draftEpoch(), result[0].revision(),
                    delta.operation().name().toLowerCase(Locale.ROOT), delta.strokeIndex(), delta.points(), null));
        }
        return result[0];
    }

    public void clear(UUID boardId, UUID slotId, UUID claimId, Instant expiresAt) {
        reset(boardId, slotId, claimId, true, expiresAt, false);
    }

    public synchronized void clear(
            UUID boardId, UUID slotId, UUID claimId, Instant expiresAt, FullUpdate fullUpdate) {
        ensureCurrent(boardId, slotId, fullUpdate);
        reset(boardId, slotId, claimId, true, expiresAt, false);
    }

    public void cancel(UUID boardId, UUID slotId, UUID claimId) {
        reset(boardId, slotId, claimId, false, null, true);
    }

    public Snapshot snapshot(UUID boardId, UUID slotId) {
        var current = drafts.get(slotId);
        if (current == null || !current.boardId().equals(boardId)) return new Snapshot(null, 0, 0);
        var signature = current.visible() && current.expiresAt().isAfter(clock.instant())
                ? current.signature().deepCopy() : null;
        return new Snapshot(signature, current.draftEpoch(), current.revision());
    }

    JsonNode signature(UUID boardId, UUID slotId) {
        return snapshot(boardId, slotId).signature();
    }

    @EventListener
    void clearSubmitted(SignatureSubmitted event) {
        reset(event.boardId(), event.slotId(), null, false, null, true);
    }

    @EventListener
    public void invalidateBoard(BoardLifecycleEvent event) {
        if ("CLOSED".equals(event.status())) invalidateBoard(event.boardId());
    }

    @EventListener
    public void invalidateBoard(BoardMutationEvent event) {
        if (!"board-updated".equals(event.type())) invalidateBoard(event.boardId());
    }

    public synchronized void invalidateBoard(UUID boardId) {
        var pending = pendingUpdates.get(boardId);
        if (pending != null) pending.generation++;
        drafts.forEach((slotId, current) -> {
            if (current.boardId().equals(boardId)) {
                reset(boardId, slotId, null, false, null, false);
            }
        });
    }

    public synchronized void fenceBoard(UUID boardId) {
        fencedBoards.add(boardId);
    }

    public synchronized void unfenceBoard(UUID boardId) {
        fencedBoards.remove(boardId);
    }

    private synchronized void finishFullUpdate(FullUpdate fullUpdate) {
        if (fullUpdate.closed) return;
        fullUpdate.closed = true;
        var pending = pendingUpdates.get(fullUpdate.boardId);
        if (pending != null && --pending.active == 0) pendingUpdates.remove(fullUpdate.boardId);
    }

    private void ensureCurrent(UUID boardId, UUID slotId, FullUpdate fullUpdate) {
        var pending = pendingUpdates.get(boardId);
        if (fullUpdate.closed || !fullUpdate.boardId.equals(boardId) || !fullUpdate.slotId.equals(slotId)
                || pending == null || pending.generation != fullUpdate.generation) {
            throw new OutOfSyncException();
        }
    }

    @Scheduled(fixedDelay = 5_000)
    void expire() {
        var now = clock.instant();
        drafts.forEach((slotId, current) -> {
            if (!current.expiresAt().isAfter(now)) {
                reset(current.boardId(), slotId, current.claimId(), false, null, false);
            }
        });
    }

    private Draft applyDelta(Draft current, DraftDelta delta) {
        var signature = current.signature().deepCopy();
        var strokes = (ArrayNode) signature.get("strokes");
        var openStroke = current.openStroke();
        switch (delta.operation()) {
            case BEGIN -> {
                if (openStroke >= 0 || delta.strokeIndex() != strokes.size() || delta.points().isEmpty()) {
                    throw new OutOfSyncException();
                }
                var stroke = json.createObjectNode();
                var points = stroke.putArray("points");
                append(points, delta);
                strokes.add(stroke);
                openStroke = delta.strokeIndex();
            }
            case APPEND -> {
                if (openStroke != delta.strokeIndex() || delta.points().isEmpty()) {
                    throw new OutOfSyncException();
                }
                append((ArrayNode) strokes.get(delta.strokeIndex()).get("points"), delta);
            }
            case END -> {
                if (openStroke != delta.strokeIndex() || !delta.points().isEmpty()) {
                    throw new OutOfSyncException();
                }
                openStroke = -1;
            }
        }
        validateCaps(strokes, signature);
        return new Draft(current.boardId(), current.claimId(), signature, true, current.expiresAt(),
                current.draftEpoch(), current.revision() + 1, delta.clientSequence(), delta, openStroke);
    }

    private void append(ArrayNode target, DraftDelta delta) {
        for (var point : delta.points()) {
            if (point.x() < 0 || point.x() > SignatureLimits.MAX_COORDINATE
                    || point.y() < 0 || point.y() > SignatureLimits.MAX_COORDINATE) {
                throw new InvalidDeltaException();
            }
            target.add(json.createObjectNode().put("x", point.x()).put("y", point.y()));
        }
    }

    private void validateCaps(ArrayNode strokes, ObjectNode signature) {
        if (strokes.size() > SignatureLimits.MAX_STROKES) throw new InvalidDeltaException();
        var points = 0;
        for (var stroke : strokes) points += stroke.get("points").size();
        if (points > SignatureLimits.MAX_POINTS) throw new InvalidDeltaException();
        try {
            if (json.writeValueAsBytes(signature).length > SignatureLimits.MAX_PAYLOAD_BYTES) {
                throw new InvalidDeltaException();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Canonical draft was not JSON", exception);
        }
    }

    private ObjectNode emptySignature() {
        return json.createObjectNode().put("version", 1).set("strokes", json.createArrayNode());
    }

    private synchronized void reset(
            UUID boardId,
            UUID slotId,
            UUID claimId,
            boolean retainClaim,
            Instant renewedExpiresAt,
            boolean advanceAbsent) {
        var changed = new boolean[1];
        var updated = new Draft[1];
        drafts.compute(slotId, (id, current) -> {
            if (current == null) {
                if (!retainClaim && !advanceAbsent) return null;
                changed[0] = true;
                updated[0] = new Draft(boardId, claimId, emptySignature(), retainClaim,
                        renewedExpiresAt == null ? Instant.MAX : renewedExpiresAt,
                        1, 0, 0, null, -1);
                return updated[0];
            }
            if (!current.boardId().equals(boardId)
                    || (claimId != null && !claimId.equals(current.claimId()))
                    || (!current.visible() && current.claimId() == null)) return current;
            changed[0] = current.visible() || current.claimId() != null;
            updated[0] = new Draft(boardId, retainClaim ? current.claimId() : null,
                    emptySignature(), retainClaim,
                    renewedExpiresAt == null ? current.expiresAt() : renewedExpiresAt,
                    current.draftEpoch() + 1, 0, 0, null, -1);
            return updated[0];
        });
        if (changed[0]) {
            boards.publish(boardId, "signature-draft-cleared");
            publishDraft(slotId, updated[0], "clear", -1, List.of());
        }
    }

    private void publishDraft(UUID slotId, Draft draft, String operation,
            int strokeIndex, List<DraftDelta.Point> points) {
        publicBoards.publish(draft.boardId(), "signature-draft", new PublicDraftEvent(
                slotId, draft.draftEpoch(), draft.revision(), operation, strokeIndex, points,
                draft.visible() ? draft.signature().deepCopy() : NullNode.getInstance()));
    }

    public record Version(long draftEpoch, long revision) {}
    public record DeltaResult(long draftEpoch, long revision, boolean duplicate) {}
    public record Snapshot(JsonNode signature, long draftEpoch, long revision) {}

    public final class FullUpdate implements AutoCloseable {
        private final UUID boardId;
        private final UUID slotId;
        private final long draftEpoch;
        private final long generation;
        private boolean closed;

        private FullUpdate(UUID boardId, UUID slotId, long draftEpoch, long generation) {
            this.boardId = boardId;
            this.slotId = slotId;
            this.draftEpoch = draftEpoch;
            this.generation = generation;
        }

        @Override
        public void close() {
            finishFullUpdate(this);
        }
    }

    public static final class OutOfSyncException extends RuntimeException {}
    public static final class InvalidDeltaException extends RuntimeException {}

    private record Draft(
            UUID boardId,
            UUID claimId,
            ObjectNode signature,
            boolean visible,
            Instant expiresAt,
            long draftEpoch,
            long revision,
            long clientSequence,
            DraftDelta lastDelta,
            int openStroke) {}

    private static final class PendingUpdates {
        private long generation;
        private int active;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record PublicDraftEvent(
            UUID slotId,
            long draftEpoch,
            long revision,
            String operation,
            int strokeIndex,
            List<DraftDelta.Point> points,
            JsonNode signature) {}
}
