package com.naraesigning.deletion;

import com.naraesigning.realtime.BoardMutationEvent;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;

final class BoardDeletionService {
    private final BoardDeletionStore store;
    private final ApplicationEventPublisher events;

    BoardDeletionService(BoardDeletionStore store, ApplicationEventPublisher events) {
        this.store = store;
        this.events = events;
    }

    void delete(UUID ownerId, UUID boardId) {
        if (store.begin(ownerId, boardId)) {
            events.publishEvent(new BoardMutationEvent(boardId, "board-deleted"));
        }
    }
}
