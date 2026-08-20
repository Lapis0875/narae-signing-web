package com.naraesigning.board.core;

public final class BoardUnavailableException extends RuntimeException {
    public BoardUnavailableException() {
        super("BOARD_UNAVAILABLE");
    }
}
