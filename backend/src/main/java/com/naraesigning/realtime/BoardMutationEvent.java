package com.naraesigning.realtime;

import java.util.Set;
import java.util.UUID;

public record BoardMutationEvent(UUID boardId, String type) {
    private static final Set<String> TYPES = Set.of(
            "background-updated", "board-updated", "layout-updated", "signature-reset");

    public BoardMutationEvent {
        if (boardId == null || !TYPES.contains(type)) throw new IllegalArgumentException("Unsupported board mutation event");
    }
}
