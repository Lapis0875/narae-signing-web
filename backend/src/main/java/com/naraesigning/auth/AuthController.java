package com.naraesigning.auth;

import com.google.common.net.InetAddresses;
import com.naraesigning.session.AdminSessionContract;
import com.naraesigning.session.SessionCookieActions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty("spring.datasource.url")
final class AuthController {
    private final AuthService auth;
    private final SessionCookieActions cookies;
    private final Clock clock;

    AuthController(AuthService auth, SessionCookieActions cookies, Clock authClock) {
        this.auth = auth;
        this.cookies = cookies;
        this.clock = authClock;
    }

    @PostMapping("/login")
    LoginResponse login(
            @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        char[] credential = body.password();
        try {
            String clientIp = clientIp(request.getHeader("X-Narae-Client-IP"));
            var adminUserId = auth.login(body.email(), credential, clientIp);
            Instant issuedAt = clock.instant();
            cookies.adminAuthenticated(request, response);
            AdminSessionContract.issue(request.getSession(false), adminUserId, issuedAt);
            return new LoginResponse(true, issuedAt.plus(AdminSessionContract.ABSOLUTE_LIFETIME));
        } finally {
            AuthService.clear(credential);
        }
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            auth.logout(session);
        }
        cookies.logoutAdmin(request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session")
    SessionResponse session(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        Instant now = clock.instant();
        if (session == null || !AdminSessionContract.isCurrent(session, now)) {
            if (session != null) {
                session.invalidate();
                cookies.logoutAdmin(request, response);
            }
            return new SessionResponse(false, null);
        }
        Instant issuedAt = (Instant) session.getAttribute(AdminSessionContract.ISSUED_AT);
        return new SessionResponse(true, issuedAt.plus(AdminSessionContract.ABSOLUTE_LIFETIME));
    }

    @GetMapping("/csrf")
    ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().build();
    }

    private static String clientIp(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim()) || !InetAddresses.isInetAddress(value)) {
            throw AuthApiException.invalidClientIp();
        }
        return InetAddresses.toAddrString(InetAddresses.forString(value));
    }

    record LoginRequest(String email, char[] password) {}

    record LoginResponse(boolean authenticated, Instant expiresAt) {}

    record SessionResponse(boolean authenticated, Instant expiresAt) {}
}
