package com.naraesigning.board.api;

final class BoardLifecycleException extends RuntimeException {
    BoardLifecycleException(String code) {
        super(code);
    }
}
