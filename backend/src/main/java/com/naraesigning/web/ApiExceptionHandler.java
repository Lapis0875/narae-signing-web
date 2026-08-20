package com.naraesigning.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformedBody(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return respond(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST_BODY",
                "요청 본문을 확인해 주세요.",
                exception,
                request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return respond(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "요청을 처리하지 못했습니다.",
                exception,
                request);
    }

    private static ResponseEntity<ApiError> respond(
            HttpStatus status,
            String code,
            String message,
            Exception exception,
            HttpServletRequest request) {
        var requestId = (String) request.getAttribute(RequestCorrelationFilter.ATTRIBUTE);
        LOGGER.warn(
                "api_request_failed code={} requestId={} exceptionType={}",
                code,
                requestId,
                exception.getClass().getSimpleName());
        return ResponseEntity.status(status).body(new ApiError(code, message, requestId));
    }
}
