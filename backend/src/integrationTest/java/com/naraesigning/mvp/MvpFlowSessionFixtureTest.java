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
import java.time.Clock;
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
        var sessionFilter = sessionFilter();
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
        // Given: the fixture seeds an administrator contract through the real session filter.
        var sessionFilter = sessionFilter();
        var request = MvpFlowFixture.admin(get("/api/v1/admin/probe"), sessionFilter)
                .buildRequest(new MockServletContext());
        var response = new MockHttpServletResponse();
        var downstream = new AtomicBoolean();
        var adminFilter = adminFilter();

        // When: the route request passes through the same session and administrator filters as the API.
        sessionFilter.doFilter(request, response,
                (filteredRequest, filteredResponse) -> adminFilter.doFilter(filteredRequest, filteredResponse,
                        (ignoredRequest, ignoredResponse) -> downstream.set(true)));

        // Then: ADMIN_SESSION resolves to a current persisted session and reaches downstream with 200.
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(downstream).isTrue();
        assertThat(Arrays.stream(request.getCookies()).map(cookie -> cookie.getName()))
                .contains("ADMIN_SESSION")
                .doesNotContain("SIGNER_SESSION");
    }

    @Test
    void signerFixturePersistsRouteCookieAcceptedByRealSessionFilter() throws Exception {
        // Given: the fixture seeds a signer contract through the real public-route session filter.
        var sessionFilter = sessionFilter();
        var request = MvpFlowFixture.signer(get("/api/v1/public/probe"), SLOT, 1, 4d / 3d, sessionFilter)
                .buildRequest(new MockServletContext());
        var response = new MockHttpServletResponse();
        var resolved = new AtomicReference<SignatureSession>();

        // When: the public route resolves the fixture cookie through SessionRepositoryFilter.
        sessionFilter.doFilter(request, response, (filteredRequest, ignoredResponse) -> resolved.set(
                SignatureSession.from(((HttpServletRequest) filteredRequest).getSession(false), NOW)));

        // Then: SIGNER_SESSION restores the exact contract without exposing its private attribute keys.
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(resolved.get()).isEqualTo(new SignatureSession(new SignerSessionContract.Value(
                BOARD, SLOT, 1, 1, 4d / 3d, NOW.minusSeconds(60)), true));
        assertThat(Arrays.stream(request.getCookies()).map(cookie -> cookie.getName()))
                .contains("SIGNER_SESSION")
                .doesNotContain("ADMIN_SESSION");
    }

    private static SessionRepositoryFilter<MapSession> sessionFilter() {
        var filter = new SessionRepositoryFilter<>(new MapSessionRepository(new ConcurrentHashMap<>()));
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
