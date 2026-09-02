package com.naraesigning.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.signature.SignatureSubmitted;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    public void update(
            UUID boardId, UUID slotId, UUID claimId, byte[] canonicalPayload, Instant expiresAt) {
        try {
            drafts.put(slotId, new Draft(boardId, claimId, json.readTree(canonicalPayload), expiresAt));
            publish(boardId, "signature-draft");
        } catch (IOException exception) {
            throw new IllegalStateException("Validated signature payload was not JSON", exception);
        }
    }

    public void clear(UUID boardId, UUID slotId, UUID claimId) {
        var current = drafts.get(slotId);
        if (current != null && current.boardId().equals(boardId)
                && current.claimId().equals(claimId) && drafts.remove(slotId, current)) {
            publish(boardId, "signature-draft-cleared");
        }
    }

    JsonNode signature(UUID boardId, UUID slotId) {
        var current = drafts.get(slotId);
        if (current == null || !current.boardId().equals(boardId)) return null;
        if (!current.expiresAt().isAfter(clock.instant())) return null;
        return current.signature().deepCopy();
    }

    @EventListener
    void clearSubmitted(SignatureSubmitted event) {
        var current = drafts.get(event.slotId());
        if (current != null && current.boardId().equals(event.boardId())
                && drafts.remove(event.slotId(), current)) {
            publish(event.boardId(), "signature-draft-cleared");
        }
    }

    @Scheduled(fixedDelay = 5_000)
    void expire() {
        var now = clock.instant();
        drafts.forEach((slotId, current) -> {
            if (!current.expiresAt().isAfter(now) && drafts.remove(slotId, current)) {
                publish(current.boardId(), "signature-draft-cleared");
            }
        });
    }

    private void publish(UUID boardId, String type) {
        boards.publish(boardId, type);
        publicBoards.publish(boardId, type);
    }

    private record Draft(UUID boardId, UUID claimId, JsonNode signature, Instant expiresAt) {}
}
