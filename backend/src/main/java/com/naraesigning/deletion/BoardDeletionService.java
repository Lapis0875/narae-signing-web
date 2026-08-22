package com.naraesigning.deletion;

import java.util.UUID;

final class BoardDeletionService {
    private final BoardDeletionStore store;

    BoardDeletionService(BoardDeletionStore store) { this.store = store; }

    void delete(UUID ownerId, UUID boardId) { store.begin(ownerId, boardId); }
}
