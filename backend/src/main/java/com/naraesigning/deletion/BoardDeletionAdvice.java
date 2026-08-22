package com.naraesigning.deletion;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = BoardDeletionController.class)
final class BoardDeletionAdvice {
    @ExceptionHandler(BoardDeletionException.class)
    ResponseEntity<Map<String, String>> deletion(BoardDeletionException exception) {
        var status = switch (exception.code()) {
            case "UNAUTHORIZED" -> HttpStatus.UNAUTHORIZED;
            case "BOARD_UNAVAILABLE" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("code", exception.code(), "message", "요청을 처리할 수 없습니다."));
    }
}
