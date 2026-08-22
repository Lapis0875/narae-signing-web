package com.naraesigning.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.naraesigning.realtime.BoardMutationEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class BoardDeletionEventTest {
    @Test
    void givenSuccessfulPhaseAWhenDeleteReturnsThenPublishesPayloadFreeEventAfterCommit() {
        var order = new ArrayList<String>();
        var boardId = UUID.randomUUID();
        var store = new RecordingStore(order, true, false);
        ApplicationEventPublisher events = event -> {
            assertThat(store.phaseACommitted).isTrue();
            order.add("event");
            assertThat(event).isEqualTo(new BoardMutationEvent(boardId, "board-deleted"));
        };

        new BoardDeletionService(store, events).delete(UUID.randomUUID(), boardId);

        assertThat(order).containsExactly("phase-a-commit", "event");
    }

    @Test
    void givenRollbackOrIdempotentStateWhenDeleteEndsThenPublishesNothing() {
        var published = new ArrayList<Object>();
        var boardId = UUID.randomUUID();

        assertThatThrownBy(() -> new BoardDeletionService(
                new RecordingStore(new ArrayList<>(), false, true), published::add)
                .delete(UUID.randomUUID(), boardId))
                .isInstanceOf(BoardDeletionException.class)
                .hasMessage("BOARD_STATE_CONFLICT");
        new BoardDeletionService(new RecordingStore(new ArrayList<>(), false, false), published::add)
                .delete(UUID.randomUUID(), boardId);

        assertThat(published).isEmpty();
    }

    private static final class RecordingStore implements BoardDeletionStore {
        private final List<String> order;
        private final boolean transitioned;
        private final boolean fail;
        private boolean phaseACommitted;

        private RecordingStore(List<String> order, boolean transitioned, boolean fail) {
            this.order = order;
            this.transitioned = transitioned;
            this.fail = fail;
        }

        @Override public boolean begin(UUID ownerId, UUID boardId) {
            if (fail) throw new BoardDeletionException("BOARD_STATE_CONFLICT");
            phaseACommitted = transitioned;
            if (transitioned) order.add("phase-a-commit");
            return transitioned;
        }
        @Override public List<DeletionJob> claim(UUID token, Instant now, Duration lease) { return List.of(); }
        @Override public boolean renew(UUID id, UUID token, Instant now, Duration lease) { return false; }
        @Override public void complete(UUID id, UUID token) {}
        @Override public void retry(UUID id, UUID token, Instant now, int attempt) {}
        @Override public void finalizeReadyBoards() {}
    }
}
