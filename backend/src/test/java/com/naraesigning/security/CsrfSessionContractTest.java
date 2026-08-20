package com.naraesigning.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import com.naraesigning.session.PathAwareSessionIdResolver;
import com.naraesigning.session.SessionCookieActions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CsrfSessionContractTest {
    @Test
    void materializesReadableBrowserSessionCookieOnInitialGet() throws Exception {
        // Given
        var request = request("GET", "/api/v1/auth/csrf");
        var response = new MockHttpServletResponse();

        // When
        new CsrfContractFilter(new CsrfTokenContract()).doFilter(request, response, new MockFilterChain());

        // Then
        assertThat(response.getHeader("Set-Cookie"))
                .startsWith("XSRF-TOKEN=")
                .contains("Path=/", "Secure", "SameSite=Lax")
                .doesNotContain("HttpOnly", "Max-Age");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, private");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/auth/login",
        "/api/v1/auth/logout",
        "/api/v1/admin/boards",
        "/api/v1/public/links/token/identify",
        "/api/v1/public/signing-session/signature"
    })
    void rejectsEveryMutationFamilyWithValidOriginButMissingCsrf(String path) throws Exception {
        // Given
        var request = request("POST", path);
        request.addHeader("Origin", "https://localhost.test");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        // When
        new CsrfContractFilter(new CsrfTokenContract()).doFilter(request, response, chain);

        // Then
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"csrf_invalid\"");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void acceptsMatchingHeaderAndCookieForMutation() throws Exception {
        // Given
        var request = request("POST", "/api/v1/admin/boards");
        request.setCookies(new Cookie("XSRF-TOKEN", "known-token"));
        request.addHeader("X-XSRF-TOKEN", "known-token");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        // When
        new CsrfContractFilter(new CsrfTokenContract()).doFilter(request, response, chain);

        // Then
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void rejectsEmptyCsrfPairWithoutEnteringLoginChain() throws Exception {
        // Given
        var request = request("POST", "/api/v1/auth/login");
        request.setCookies(new Cookie("XSRF-TOKEN", ""));
        request.addHeader("X-XSRF-TOKEN", "");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        // When
        new CsrfContractFilter(new CsrfTokenContract()).doFilter(request, response, chain);

        // Then
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"csrf_invalid\"");
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/auth/login",
        "/api/v1/auth/logout",
        "/api/v1/admin/boards",
        "/api/v1/public/links/token/identify",
        "/api/v1/public/signing-session/signature"
    })
    void rejectsMismatchedCsrfForEveryMutationFamily(String path) throws Exception {
        // Given
        var request = request("POST", path);
        request.setCookies(new Cookie("XSRF-TOKEN", "cookie-token"));
        request.addHeader("X-XSRF-TOKEN", "header-token");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        // When
        new CsrfContractFilter(new CsrfTokenContract()).doFilter(request, response, chain);

        // Then
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"csrf_invalid\"");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rotationReplacesTokenAndLogoutClearsIt() {
        // Given
        var contract = new CsrfTokenContract();
        var rotated = new MockHttpServletResponse();
        var cleared = new MockHttpServletResponse();

        // When
        contract.rotate(rotated);
        contract.clear(cleared);

        // Then
        assertThat(rotated.getHeader("Set-Cookie"))
                .startsWith("XSRF-TOKEN=")
                .doesNotContain("known-token", "Max-Age", "HttpOnly");
        assertThat(cleared.getHeader("Set-Cookie"))
                .startsWith("XSRF-TOKEN=; Path=/; Max-Age=0;")
                .contains("Secure", "SameSite=Lax")
                .doesNotContain("HttpOnly");
    }

    @Test
    void loginAndIdentifyRotateCsrfWhileClearActionsStayScoped() {
        // Given
        var tokens = new CsrfTokenContract();
        var actions = new SessionCookieActions(new PathAwareSessionIdResolver(), tokens);
        var adminRequest = request("POST", "/api/v1/auth/login");
        var publicRequest = request("POST", "/api/v1/public/links/token/identify");
        var adminRotation = new MockHttpServletResponse();
        var signerRotation = new MockHttpServletResponse();
        var logout = new MockHttpServletResponse();
        var staleSigner = new MockHttpServletResponse();

        // When
        actions.adminAuthenticated(adminRequest, adminRotation);
        actions.signerIdentified(publicRequest, signerRotation);
        actions.logoutAdmin(adminRequest, logout);
        actions.clearStaleSigner(publicRequest, staleSigner);

        // Then
        assertThat(adminRotation.getHeader("Set-Cookie")).startsWith("XSRF-TOKEN=");
        assertThat(signerRotation.getHeader("Set-Cookie")).startsWith("XSRF-TOKEN=");
        assertThat(logout.getHeaders("Set-Cookie"))
                .anyMatch(value -> value.startsWith("ADMIN_SESSION="))
                .anyMatch(value -> value.startsWith("XSRF-TOKEN="))
                .noneMatch(value -> value.startsWith("SIGNER_SESSION="));
        assertThat(staleSigner.getHeaders("Set-Cookie"))
                .anyMatch(value -> value.startsWith("SIGNER_SESSION="))
                .noneMatch(value -> value.startsWith("ADMIN_SESSION="))
                .noneMatch(value -> value.startsWith("XSRF-TOKEN="));
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setSecure(true);
        return request;
    }
}
