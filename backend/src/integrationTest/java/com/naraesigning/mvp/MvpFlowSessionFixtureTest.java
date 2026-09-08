package com.naraesigning.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.naraesigning.security.CsrfTokenContract;
import com.naraesigning.session.AdminSessionContract;
import com.naraesigning.session.PathAwareSessionIdResolver;
import com.naraesigning.session.SessionCookieActions;
import com.naraesigning.session.SignerSessionContract;
import com.naraesigning.signature.SignatureSession;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;

final class MvpFlowSessionFixtureTest {
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final UUID OWNER = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOARD = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID SLOT = UUID.fromString("40000000-0000-4000-8000-000000000001");

    @Test
    void rawMockSessionRemainsUnauthorizedAtRepositoryBoundary() throws Exception {
        // Given: an unpersisted fixture-equivalent admin session reaches the real session and admin filters.
        var session = new MockHttpSession();
        AdminSessionContract.issue(session, OWNER, NOW.minusSeconds(60));
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/probe");
        request.setSecure(true);
        request.setSession(session);
        var response = new MockHttpServletResponse();
        var downstream = new AtomicBoolean();
        var sessionFilter = sessionFilter(sessions());
        var adminFilter = adminFilter();

        // When: SessionRepositoryFilter resolves the request before AdminBoardFilter.
        sessionFilter.doFilter(request, response,
                (filteredRequest, filteredResponse) -> adminFilter.doFilter(filteredRequest, filteredResponse,
                        (ignoredRequest, ignoredResponse) -> downstream.set(true)));

        // Then: the raw session is unavailable and authentication stops with 401.
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(downstream).isFalse();
    }

    @Test
    void adminFixturePersistsRouteCookieAcceptedByRealFilters() throws Exception {
        // Given: the fixture persists an administrator contract in the session repository.
        var sessions = sessions();
        var sessionFilter = sessionFilter(sessions);
        var request = MvpFlowFixture.admin(get("/api/v1/admin/probe"), sessions)
                .buildRequest(new MockServletContext());
        var response = new MockHttpServletResponse();
        var downstream = new AtomicBoolean();
        var resolved = new AtomicReference<HttpSession>();
        var adminFilter = adminFilter();

        // When: the route request passes through the same session and administrator filters as the API.
        sessionFilter.doFilter(request, response,
                (filteredRequest, filteredResponse) -> adminFilter.doFilter(filteredRequest, filteredResponse,
                        (resolvedRequest, ignoredResponse) -> {
                            resolved.set(((HttpServletRequest) resolvedRequest).getSession(false));
                            downstream.set(true);
                        }));

        // Then: ADMIN_SESSION resolves to a current persisted session and reaches downstream with 200.
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(downstream).isTrue();
        assertThat(AdminSessionContract.isCurrent(resolved.get(), NOW)).isTrue();
        assertThat(resolved.get().getAttribute(AdminSessionContract.ADMIN_USER_ID)).isEqualTo(OWNER.toString());
        assertThat(resolved.get().getMaxInactiveInterval())
                .isEqualTo(AdminSessionContract.ABSOLUTE_LIFETIME.toSeconds());
        assertThat(Arrays.stream(request.getCookies()).map(cookie -> cookie.getName()))
                .contains("ADMIN_SESSION")
                .doesNotContain("SIGNER_SESSION");
    }

    @Test
    void signerFixturePersistsRouteCookieAcceptedByRealSessionFilter() throws Exception {
        // Given: the fixture persists a signer contract in the session repository.
        var sessions = sessions();
        var sessionFilter = sessionFilter(sessions);
        var maximumLifetime = Duration.ofMinutes(37);
        var request = MvpFlowFixture.signer(get("/api/v1/public/probe"), SLOT, 1, 4d / 3d,
                        sessions, maximumLifetime)
                .buildRequest(new MockServletContext());
        var response = new MockHttpServletResponse();
        var resolved = new AtomicReference<SignatureSession>();
        var resolvedSession = new AtomicReference<HttpSession>();

        // When: the public route resolves the fixture cookie through SessionRepositoryFilter.
        sessionFilter.doFilter(request, response, (filteredRequest, ignoredResponse) -> {
            var session = ((HttpServletRequest) filteredRequest).getSession(false);
            resolvedSession.set(session);
            resolved.set(SignatureSession.from(session, NOW));
        });

        // Then: SIGNER_SESSION restores the exact contract without exposing its private attribute keys.
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(resolved.get()).isEqualTo(new SignatureSession(new SignerSessionContract.Value(
                BOARD, SLOT, 1, 1, 4d / 3d, NOW.minusSeconds(60)), true));
        assertThat(resolvedSession.get().getMaxInactiveInterval())
                .isEqualTo(maximumLifetime.toSeconds());
        assertThat(Arrays.stream(request.getCookies()).map(cookie -> cookie.getName()))
                .contains("SIGNER_SESSION")
                .doesNotContain("ADMIN_SESSION");
    }

    private static MapSessionRepository sessions() {
        return new MapSessionRepository(new ConcurrentHashMap<>());
    }

    private static SessionRepositoryFilter<MapSession> sessionFilter(MapSessionRepository sessions) {
        var filter = new SessionRepositoryFilter<>(sessions);
        filter.setHttpSessionIdResolver(new PathAwareSessionIdResolver());
        return filter;
    }

    private static Filter adminFilter() throws Exception {
        var constructor = Class.forName("com.naraesigning.board.api.AdminBoardFilter")
                .getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (Filter) constructor.newInstance(null,
                new SessionCookieActions(new PathAwareSessionIdResolver(), new CsrfTokenContract()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
