package com.naraesigning.signature;

import com.naraesigning.session.SignerSessionContract;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.UUID;

record SignatureSession(SignerSessionContract.Value value, boolean active) {
    static SignatureSession from(HttpSession session, Instant now) {
        if (session == null) throw SignatureSubmitException.sessionExpired();
        try {
            var value = new SignerSessionContract.Value(
                    (UUID) session.getAttribute("signer.boardId"),
                    (UUID) session.getAttribute("signer.slotId"),
                    (Integer) session.getAttribute("signer.shareLinkVersion"),
                    (Long) session.getAttribute("signer.slotRevision"),
                    (Double) session.getAttribute("signer.signatureAspectRatio"),
                    (Instant) session.getAttribute("signer.issuedAt"));
            var idleDeadline = Instant.ofEpochMilli(session.getLastAccessedTime())
                    .plusSeconds(session.getMaxInactiveInterval());
            return new SignatureSession(value,
                    SignerSessionContract.isCurrent(session, now) && now.isBefore(idleDeadline));
        } catch (RuntimeException exception) {
            throw SignatureSubmitException.sessionExpired();
        }
    }
}
