package com.naraesigning.board.core;

import java.time.Instant;
import java.util.UUID;

public record BoardView(
        UUID id,
        String title,
        String status,
        int canvasWidth,
        int canvasHeight,
        SignatureInkColor signatureInkColor,
        int shareLinkVersion,
        Instant createdAt,
        Instant updatedAt) {
    static BoardView from(StoredBoard board) {
        return new BoardView(
                board.id(),
                board.title().value(),
                statusLabel(board.status()),
                board.canvasWidth(),
                board.canvasHeight(),
                board.signatureInkColor(),
                board.share().version(),
                board.createdAt(),
                board.updatedAt());
    }

    static String statusLabel(BoardStatus status) {
        return switch (status) {
            case DRAFT -> "설정 중";
            case OPEN -> "서명 진행";
            case CLOSED -> "마감/보관";
            case DELETING -> throw new BoardUnavailableException();
        };
    }
}
