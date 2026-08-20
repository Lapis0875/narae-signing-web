package com.naraesigning.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class AuthApiAdvice {
    @ExceptionHandler(AuthApiException.class)
    ResponseEntity<AuthError> authError(AuthApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status())
                .body(new AuthError(
                        exception.code(),
                        exception.userMessage(),
                        (String) request.getAttribute("com.naraesigning.web.RequestCorrelationFilter.requestId")));
    }

    record AuthError(String code, String message, String requestId) {}
}
