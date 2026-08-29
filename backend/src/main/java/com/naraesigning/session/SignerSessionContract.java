package com.naraesigning.session;

import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class SignerSessionContract {
    private static final String BOARD_ID = "signer.boardId";
    private static final String SLOT_ID = "signer.slotId";
    private static final String LINK_VERSION = "signer.shareLinkVersion";
    private static final String SLOT_REVISION = "signer.slotRevision";
    private static final String ASPECT = "signer.signatureAspectRatio";
    private static final String ISSUED_AT = "signer.issuedAt";

    private SignerSessionContract() {}

    public static void issue(HttpSession session, Value value, Duration maximumLifetime) {
        if (maximumLifetime == null || maximumLifetime.isNegative() || maximumLifetime.isZero()) {
            throw new IllegalArgumentException("Signer session lifetime must be positive");
        }
        var maximumLifetimeSeconds = Math.toIntExact(maximumLifetime.toSeconds());
        if (maximumLifetimeSeconds < 1) {
            throw new IllegalArgumentException("Signer session lifetime must be at least one second");
        }
        session.getAttributeNames().asIterator().forEachRemaining(session::removeAttribute);
        session.setAttribute(BOARD_ID, value.boardId());
        session.setAttribute(SLOT_ID, value.slotId());
        session.setAttribute(LINK_VERSION, value.shareLinkVersion());
        session.setAttribute(SLOT_REVISION, value.slotRevision());
        session.setAttribute(ASPECT, value.signatureAspectRatio());
        session.setAttribute(ISSUED_AT, value.issuedAt());
        session.setMaxInactiveInterval(maximumLifetimeSeconds);
    }

    public static boolean isCurrent(HttpSession session, Instant now) {
        var issuedAt = (Instant) session.getAttribute(ISSUED_AT);
        var maximumLifetimeSeconds = session.getMaxInactiveInterval();
        return issuedAt != null
                && maximumLifetimeSeconds > 0
                && now.isBefore(issuedAt.plusSeconds(maximumLifetimeSeconds));
    }

    public static State validate(Value signer, CurrentState current) {
        if (current.boardStatus().equals("DELETING") || current.boardStatus().equals("UNAVAILABLE")) {
            return State.UNAVAILABLE;
        }
        if (signer.shareLinkVersion() != current.shareLinkVersion()) {
            return State.STALE_LINK;
        }
        if (signer.slotRevision() != current.slotRevision()) {
            return State.STALE_SLOT;
        }
        if (Double.compare(signer.signatureAspectRatio(), current.signatureAspectRatio()) != 0) {
            return State.STALE_ASPECT;
        }
        return current.boardStatus().equals("CLOSED") ? State.CLOSED : State.CURRENT;
    }

    public record Value(
            UUID boardId,
            UUID slotId,
            int shareLinkVersion,
            long slotRevision,
            double signatureAspectRatio,
            Instant issuedAt) {
        public Value {
            if (shareLinkVersion < 1 || slotRevision < 0 || signatureAspectRatio <= 0 || !Double.isFinite(signatureAspectRatio)) {
                throw new IllegalArgumentException("Invalid signer session contract");
            }
        }
    }

    public record CurrentState(
            int shareLinkVersion, long slotRevision, double signatureAspectRatio, String boardStatus) {}

    public enum State {
        CURRENT(false),
        CLOSED(false),
        STALE_LINK(true),
        STALE_SLOT(true),
        STALE_ASPECT(true),
        UNAVAILABLE(true);

        private final boolean clearSignerSession;

        State(boolean clearSignerSession) {
            this.clearSignerSession = clearSignerSession;
        }

        public boolean clearsSignerSession() {
            return clearSignerSession;
        }
    }
}
