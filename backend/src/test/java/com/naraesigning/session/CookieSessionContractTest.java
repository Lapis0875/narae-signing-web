package com.naraesigning.session;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CookieSessionContractTest {
    @Test
    void usesOnlyAdminCookieWhenRequestIsAdminRoute() {
        // Given
        var request = request("/api/v1/admin/boards");
        request.setCookies(new Cookie("ADMIN_SESSION", "admin-id"), new Cookie("SIGNER_SESSION", "signer-id"));

        // When
        var ids = new PathAwareSessionIdResolver().resolveSessionIds(request);

        // Then
        assertThat(ids).containsExactly("admin-id");
    }

    @Test
    void usesOnlySignerCookieWhenRequestIsPublicRoute() {
        // Given
        var request = request("/api/v1/public/signing-session");
        request.setCookies(new Cookie("ADMIN_SESSION", "admin-id"), new Cookie("SIGNER_SESSION", "signer-id"));

        // When
        var ids = new PathAwareSessionIdResolver().resolveSessionIds(request);

        // Then
        assertThat(ids).containsExactly("signer-id");
    }

    @Test
    void doesNotTreatSimilarPrefixAsPublicRoute() {
        // Given
        var request = request("/api/v1/publicity");
        request.setCookies(new Cookie("ADMIN_SESSION", "admin-id"), new Cookie("SIGNER_SESSION", "signer-id"));

        // When
        var ids = new PathAwareSessionIdResolver().resolveSessionIds(request);

        // Then
        assertThat(ids).containsExactly("admin-id");
    }

    @Test
    void rejectsWrongRouteFamilyCookie() {
        // Given
        var adminRoute = request("/api/v1/admin/boards");
        adminRoute.setCookies(new Cookie("SIGNER_SESSION", "signer-id"));
        var publicRoute = request("/api/v1/public/signing-session");
        publicRoute.setCookies(new Cookie("ADMIN_SESSION", "admin-id"));

        // When / Then
        assertThat(new PathAwareSessionIdResolver().resolveSessionIds(adminRoute)).isEmpty();
        assertThat(new PathAwareSessionIdResolver().resolveSessionIds(publicRoute)).isEmpty();
    }

    @Test
    void writesExactScopedSecureAuthenticationCookies() {
        // Given
        var resolver = new PathAwareSessionIdResolver();
        var adminResponse = new MockHttpServletResponse();
        var signerResponse = new MockHttpServletResponse();

        // When
        resolver.setSessionId(request("/api/v1/auth/login"), adminResponse, "admin-id");
        resolver.setSessionId(request("/api/v1/public/links/token/identify"), signerResponse, "signer-id");

        // Then
        assertThat(adminResponse.getHeader("Set-Cookie"))
                .isEqualTo("ADMIN_SESSION=admin-id; Path=/api/v1; Secure; HttpOnly; SameSite=Lax");
        assertThat(signerResponse.getHeader("Set-Cookie"))
                .isEqualTo("SIGNER_SESSION=signer-id; Path=/api/v1/public; Secure; HttpOnly; SameSite=Lax");
    }

    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.setSecure(true);
        return request;
    }
}
