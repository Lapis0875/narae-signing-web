package com.naraesigning.session;

import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.session.FindByIndexNameSessionRepository;

public final class AdminSessionContract {
    public static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(12);
    public static final String ISSUED_AT = "admin.issuedAt";
    public static final String ADMIN_USER_ID = FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME;

    private AdminSessionContract() {}

    public static void issue(HttpSession session, UUID adminUserId, Instant issuedAt) {
        session.setAttribute(ADMIN_USER_ID, adminUserId.toString());
        session.setAttribute(ISSUED_AT, issuedAt);
        session.setMaxInactiveInterval(Math.toIntExact(ABSOLUTE_LIFETIME.toSeconds()));
    }

    public static boolean isCurrent(HttpSession session, Instant now) {
        var issuedAt = (Instant) session.getAttribute(ISSUED_AT);
        return issuedAt != null && now.isBefore(issuedAt.plus(ABSOLUTE_LIFETIME));
    }
}
