package com.naraesigning.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.realtime.DraftDelta;
import com.naraesigning.realtime.LiveSignatureRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BoardDeletionDraftFenceTest {
    private static final UUID BOARD = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SLOT = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID CLAIM = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void deletionFencesCachedDeltaBeforeTheDeletingTransactionBegins() throws Exception {
        // Given
        var drafts = registry();
        var version = fullDraft(drafts);
        var rejectedDuringTransition = new AtomicBoolean();
        var store = new TransitionStore(() -> rejectedDuringTransition.set(rejected(drafts, version)), false);
        var service = new BoardDeletionService(store, event -> drafts.invalidateBoard(BOARD), drafts);

        // When
        service.delete(UUID.randomUUID(), BOARD);

        // Then
        assertThat(rejectedDuringTransition).isTrue();
        assertThatThrownBy(() -> apply(drafts, version))
                .isInstanceOf(LiveSignatureRegistry.OutOfSyncException.class);
    }

    @Test
    void deletionRollbackUnfencesAndPreservesTheCachedDraft() throws Exception {
        // Given
        var drafts = registry();
        var version = fullDraft(drafts);
        var rejectedDuringTransition = new AtomicBoolean();
        var store = new TransitionStore(() -> rejectedDuringTransition.set(rejected(drafts, version)), true);
        var service = new BoardDeletionService(store, event -> drafts.invalidateBoard(BOARD), drafts);

        // When / Then
        assertThatThrownBy(() -> service.delete(UUID.randomUUID(), BOARD))
                .isInstanceOf(BoardDeletionException.class);
        assertThat(rejectedDuringTransition).isTrue();
        assertThat(apply(drafts, version).revision()).isOne();
    }

    private static LiveSignatureRegistry registry() throws Exception {
        var constructor = LiveSignatureRegistry.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var parameterTypes = constructor.getParameterTypes();
        return (LiveSignatureRegistry) constructor.newInstance(new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), mock(parameterTypes[2]), mock(parameterTypes[3]));
    }

    private static LiveSignatureRegistry.Version fullDraft(LiveSignatureRegistry drafts) {
        return drafts.update(BOARD, SLOT, CLAIM,
                "{\"version\":1,\"strokes\":[]}".getBytes(StandardCharsets.UTF_8), NOW.plusSeconds(90));
    }

    private static LiveSignatureRegistry.DeltaResult apply(
            LiveSignatureRegistry drafts, LiveSignatureRegistry.Version version) {
        return drafts.apply(BOARD, SLOT, CLAIM,
                new DraftDelta(DraftDelta.Operation.BEGIN, 1, version.draftEpoch(), 0, 0,
                        List.of(new DraftDelta.Point(1, 2))),
                () -> NOW.plusSeconds(90));
    }

    private static boolean rejected(LiveSignatureRegistry drafts, LiveSignatureRegistry.Version version) {
        try {
            apply(drafts, version);
            return false;
        } catch (LiveSignatureRegistry.OutOfSyncException exception) {
            return true;
        }
    }

    private record TransitionStore(Runnable duringBegin, boolean fail) implements BoardDeletionStore {
        @Override public boolean begin(UUID ownerId, UUID boardId) {
            duringBegin.run();
            if (fail) throw new BoardDeletionException("BOARD_STATE_CONFLICT");
            return true;
        }
        @Override public List<DeletionJob> claim(UUID token, Instant now, Duration lease) { return List.of(); }
        @Override public boolean renew(UUID id, UUID token, Instant now, Duration lease) { return false; }
        @Override public void complete(UUID id, UUID token) {}
        @Override public void retry(UUID id, UUID token, Instant now, int attempt) {}
        @Override public void finalizeReadyBoards() {}
    }
}
