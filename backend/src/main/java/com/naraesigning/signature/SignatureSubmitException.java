package com.naraesigning.signature;

final class SignatureSubmitException extends RuntimeException {
    private final String code;
    private final int status;
    private final boolean clearSession;

    private SignatureSubmitException(String code, int status, boolean clearSession) {
        super(code);
        this.code = code;
        this.status = status;
        this.clearSession = clearSession;
    }

    static SignatureSubmitException invalidPayload() {
        return new SignatureSubmitException("signature_invalid", 400, false);
    }

    static SignatureSubmitException sessionExpired() {
        return new SignatureSubmitException("signer_session_expired", 401, true);
    }

    static SignatureSubmitException stale() {
        return new SignatureSubmitException("signer_stale", 409, true);
    }

    static SignatureSubmitException closed() {
        return new SignatureSubmitException("board_closed", 409, false);
    }

    static SignatureSubmitException alreadySubmitted() {
        return new SignatureSubmitException("signature_already_submitted", 409, false);
    }

    static SignatureSubmitException invalidState() {
        return new SignatureSubmitException("signature_state_invalid", 409, true);
    }

    String code() {
        return code;
    }

    int status() {
        return status;
    }

    boolean clearsSession() {
        return clearSession;
    }
}
