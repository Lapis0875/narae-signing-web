package com.naraesigning.deletion;

import com.naraesigning.realtime.BoardMutationEvent;
import com.naraesigning.realtime.LiveSignatureRegistry;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;

final class BoardDeletionService {
    private final BoardDeletionStore store;
    private final ApplicationEventPublisher events;
    private final LiveSignatureRegistry drafts;

    BoardDeletionService(BoardDeletionStore store, ApplicationEventPublisher events, LiveSignatureRegistry drafts) {
        this.store = store;
        this.events = events;
        this.drafts = drafts;
    }

    void delete(UUID ownerId, UUID boardId) {
        drafts.fenceBoard(boardId);
        try {
            var transitioned = store.begin(ownerId, boardId);
            drafts.invalidateBoard(boardId);
            if (transitioned) events.publishEvent(new BoardMutationEvent(boardId, "board-deleted"));
        } finally {
            drafts.unfenceBoard(boardId);
        }
    }
}
