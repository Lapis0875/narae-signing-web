package com.naraesigning.deletion;

final class BoardDeletionException extends RuntimeException {
    private final String code;

    BoardDeletionException(String code) {
        super(code);
        this.code = code;
    }

    String code() { return code; }
}
