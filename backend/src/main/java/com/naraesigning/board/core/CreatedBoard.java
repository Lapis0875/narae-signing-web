package com.naraesigning.board.core;

public record CreatedBoard(BoardView board, String shareToken) {
    @Override
    public String toString() {
        return "CreatedBoard[board=" + board + ", shareToken=[REDACTED]]";
    }
}
