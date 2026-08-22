package com.naraesigning.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class ApiExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformedBody(HttpServletRequest request) {
        return respond(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST_BODY",
                "요청 본문을 확인해 주세요.",
                request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(HttpServletRequest request) {
        return respond(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "요청을 처리하지 못했습니다.",
                request);
    }

    private static ResponseEntity<ApiError> respond(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request) {
        var requestId = (String) request.getAttribute(RequestCorrelationFilter.ATTRIBUTE);
        RequestCorrelationFilter.errorCode(request, code);
        return ResponseEntity.status(status).body(new ApiError(code, message, requestId));
    }
}
