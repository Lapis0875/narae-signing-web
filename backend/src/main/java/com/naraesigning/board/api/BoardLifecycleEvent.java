package com.naraesigning.board.api;

import java.util.UUID;

public record BoardLifecycleEvent(UUID boardId, String status) {}
