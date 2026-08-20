package com.naraesigning.auth;

import org.springframework.http.HttpStatus;

final class AuthApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String userMessage;

    private AuthApiException(HttpStatus status, String code, String userMessage) {
        super(code);
        this.status = status;
        this.code = code;
        this.userMessage = userMessage;
    }

    static AuthApiException authenticationFailed() {
        return new AuthApiException(
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_FAILED",
                "이메일 또는 비밀번호를 확인해 주세요.");
    }

    static AuthApiException rateLimited() {
        return new AuthApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "LOGIN_RATE_LIMITED",
                "잠시 후 다시 시도해 주세요.");
    }

    static AuthApiException invalidClientIp() {
        return new AuthApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_CLIENT_IP",
                "요청을 확인해 주세요.");
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    String userMessage() {
        return userMessage;
    }
}
