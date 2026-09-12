package com.naraesigning.board.api;

import com.naraesigning.background.BackgroundInputException;
import com.naraesigning.background.BackgroundStoreException;
import com.naraesigning.board.core.BoardUnavailableException;
import com.naraesigning.board.core.InvalidBoardTitleException;
import com.naraesigning.slot.SlotConflictException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = BoardAdminController.class)
final class BoardAdminApiAdvice {
    @ExceptionHandler(BoardUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return response(HttpStatus.NOT_FOUND, "BOARD_UNAVAILABLE");
    }

    @ExceptionHandler(InvalidBoardTitleException.class)
    ResponseEntity<Map<String, String>> title() {
        return response(HttpStatus.BAD_REQUEST, "BOARD_TITLE_INVALID");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidRequest() {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadableRequest() {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    @ExceptionHandler(BoardPatchInputException.class)
    ResponseEntity<Map<String, String>> patch(BoardPatchInputException exception) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(BoardLifecycleException.class)
    ResponseEntity<Map<String, String>> lifecycle(BoardLifecycleException exception) {
        return response(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(SlotConflictException.class)
    ResponseEntity<Map<String, String>> slot(SlotConflictException exception) {
        return response(HttpStatus.CONFLICT, exception.code().name());
    }

    @ExceptionHandler(BackgroundInputException.class)
    ResponseEntity<Map<String, String>> background(BackgroundInputException exception) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(BackgroundStoreException.class)
    ResponseEntity<Map<String, String>> backgroundStore() {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "BACKGROUND_UNAVAILABLE");
    }

    private static ResponseEntity<Map<String, String>> response(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}

final class BoardPatchInputException extends RuntimeException {
    BoardPatchInputException(String code) { super(code); }
}
