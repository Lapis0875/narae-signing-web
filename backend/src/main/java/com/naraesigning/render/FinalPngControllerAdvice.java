package com.naraesigning.render;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class FinalPngControllerAdvice {
    @ExceptionHandler(FinalPngException.class)
    ResponseEntity<Map<String, String>> handle(FinalPngException exception) {
        var status = switch (exception.code()) {
            case "UNAUTHORIZED" -> HttpStatus.UNAUTHORIZED;
            case "FINAL_PNG_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "FINAL_PNG_NOT_CLOSED" -> HttpStatus.CONFLICT;
            case "FINAL_PNG_BUSY" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        var response = ResponseEntity.status(status);
        if ("FINAL_PNG_BUSY".equals(exception.code())) response.header("Retry-After", "1");
        return response.contentType(MediaType.APPLICATION_JSON).body(Map.of("code", exception.code()));
    }
}
