package com.naraesigning.signer.identify;

import org.springframework.http.HttpStatus;

final class PublicIdentifyException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final int retryAfterSeconds;

    private PublicIdentifyException(HttpStatus status, String code, int retryAfterSeconds) {
        super(code);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    static PublicIdentifyException denied() {
        return new PublicIdentifyException(HttpStatus.FORBIDDEN, "IDENTIFICATION_FAILED", 0);
    }

    static PublicIdentifyException invalidClientIp() {
        return new PublicIdentifyException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", 0);
    }

    static PublicIdentifyException sessionExpired() {
        return new PublicIdentifyException(HttpStatus.UNAUTHORIZED, "SIGNER_SESSION_EXPIRED", 0);
    }

    static PublicIdentifyException rateLimited(int retryAfterSeconds) {
        return new PublicIdentifyException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", retryAfterSeconds);
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
