package com.naraesigning.board.core;

record BoardTitle(String value) {
    BoardTitle {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 120
                || value.codePoints().anyMatch(codePoint -> codePoint >= 0xd800 && codePoint <= 0xdfff)) {
            throw new InvalidBoardTitleException();
        }
    }
}
