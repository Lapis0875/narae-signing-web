package com.naraesigning.signer.identify;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.naraesigning.session.SignerSessionContract;
import com.naraesigning.signature.SignatureSession;
import com.naraesigning.slot.CanonicalAspect;
import java.math.BigDecimal;

final class PublicSigningSessionService {
    private final PublicIdentifyRepository repository;

    PublicSigningSessionService(PublicIdentifyRepository repository) {
        this.repository = repository;
    }

    SigningSessionResult read(SignatureSession session) {
        if (!session.active()) {
            throw PublicIdentifyException.sessionExpired();
        }
        var signer = session.value();
        var current = repository.findSigner(signer.boardId(), signer.slotId()).orElse(null);
        if (current == null) {
            return invalid();
        }
        if (!"PLACED".equals(current.placementStatus())
                || current.slotWidth() == null
                || current.slotHeight() == null) {
            return invalid();
        }
        double aspect;
        try {
            aspect = CanonicalAspect.from(
                            current.slotWidth().multiply(BigDecimal.valueOf(current.canvasWidth())),
                            current.slotHeight().multiply(BigDecimal.valueOf(current.canvasHeight())))
                    .value().doubleValue();
        } catch (IllegalArgumentException exception) {
            return invalid();
        }
        var state = SignerSessionContract.validate(signer, new SignerSessionContract.CurrentState(
                current.linkVersion(), current.slotRevision(), aspect, current.boardStatus()));
        return switch (state) {
            case STALE_LINK, STALE_SLOT, STALE_ASPECT -> stale();
            case UNAVAILABLE -> invalid();
            case CLOSED -> current.submitted() ? submitted() : closed();
            case CURRENT -> currentState(current, signer.signatureAspectRatio());
        };
    }

    private static SigningSessionResult currentState(SignerRecord current, double aspect) {
        if (!"OPEN".equals(current.boardStatus())) {
            return invalid();
        }
        return current.submitted() ? submitted() : ready(aspect);
    }

    private static SigningSessionResult ready(double aspect) {
        return new SigningSessionResult(
                new SigningSessionResponse(SigningSessionState.READY, aspect), false);
    }

    private static SigningSessionResult submitted() {
        return state(SigningSessionState.SUBMITTED, false);
    }

    private static SigningSessionResult closed() {
        return state(SigningSessionState.CLOSED, false);
    }

    private static SigningSessionResult stale() {
        return state(SigningSessionState.STALE, true);
    }

    private static SigningSessionResult invalid() {
        return state(SigningSessionState.INVALID, true);
    }

    private static SigningSessionResult state(SigningSessionState state, boolean clearSession) {
        return new SigningSessionResult(new SigningSessionResponse(state, null), clearSession);
    }
}

record SigningSessionResult(SigningSessionResponse response, boolean clearSession) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
record SigningSessionResponse(SigningSessionState state, Double signatureAspectRatio) {}

enum SigningSessionState {
    READY,
    SUBMITTED,
    CLOSED,
    STALE,
    INVALID
}
