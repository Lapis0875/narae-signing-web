package com.naraesigning.signer.identify;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        PublicIdentifyController.class,
        PublicSigningSessionController.class
})
final class PublicIdentifyAdvice {
    @ExceptionHandler(PublicIdentifyException.class)
    ResponseEntity<PublicIdentifyError> identifyError(PublicIdentifyException exception) {
        var response = ResponseEntity.status(exception.status());
        if (exception.retryAfterSeconds() > 0) {
            response.header("Retry-After", Integer.toString(exception.retryAfterSeconds()));
        }
        return response.body(new PublicIdentifyError(
                exception.code(),
                "IDENTIFICATION_FAILED".equals(exception.code())
                        ? "입력 정보를 확인해 주세요."
                        : "잠시 후 다시 시도해 주세요."));
    }
}

record PublicIdentifyError(String code, String message) {}
