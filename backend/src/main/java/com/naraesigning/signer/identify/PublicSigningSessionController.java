package com.naraesigning.signer.identify;

import com.naraesigning.session.SessionCookieActions;
import com.naraesigning.session.SignerSessionContract;
import com.naraesigning.signature.SignatureSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/signing-session")
@ConditionalOnProperty("spring.datasource.url")
final class PublicSigningSessionController {
    private final PublicSigningSessionService sessions;
    private final SessionCookieActions cookies;
    private final Clock clock;

    PublicSigningSessionController(
            PublicSigningSessionService sessions, SessionCookieActions cookies, Clock clock) {
        this.sessions = sessions;
        this.cookies = cookies;
        this.clock = clock;
    }

    @GetMapping
    SigningSessionResponse read(HttpServletRequest request, HttpServletResponse response) {
        var httpSession = request.getSession(false);
        SignatureSession session;
        try {
            session = SignatureSession.from(httpSession, clock.instant());
        } catch (RuntimeException exception) {
            clear(httpSession, request, response);
            throw PublicIdentifyException.sessionExpired();
        }
        if (!session.active()) {
            clear(httpSession, request, response);
            throw PublicIdentifyException.sessionExpired();
        }
        var result = sessions.read(session, SignerSessionContract.draftClaimId(httpSession), clock.instant());
        if (result.clearSession()) {
            clear(httpSession, request, response);
        }
        return result.response();
    }

    private void clear(
            jakarta.servlet.http.HttpSession session,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (session != null) {
            session.invalidate();
            cookies.clearStaleSigner(request, response);
        }
    }
}
