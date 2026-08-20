package com.naraesigning.board.core;

import java.util.UUID;

public record PublicBoardLink(UUID boardId, String title, String status, int shareLinkVersion) {}
