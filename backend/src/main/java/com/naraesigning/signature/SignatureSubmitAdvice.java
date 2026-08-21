package com.naraesigning.signature;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SignatureSubmitController.class)
final class SignatureSubmitAdvice {
    @ExceptionHandler(SignatureSubmitException.class)
    ResponseEntity<SignatureSubmitError> submitError(SignatureSubmitException exception) {
        return ResponseEntity.status(exception.status()).body(new SignatureSubmitError(
                exception.code(), "서명 요청을 처리할 수 없습니다."));
    }
}

record SignatureSubmitError(String code, String message) {}
