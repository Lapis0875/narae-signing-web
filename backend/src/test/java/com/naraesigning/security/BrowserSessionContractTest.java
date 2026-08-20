package com.naraesigning.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.naraesigning.session.PathAwareSessionIdResolver;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

class BrowserSessionContractTest {
    @Test
    void coexistingCookiesReachOnlyTheirHttpsRouteFamilyWithCsrf() throws Exception {
        // Given
        var resolver = new PathAwareSessionIdResolver();
        var mvc = MockMvcBuilders.standaloneSetup(new ProbeController(resolver))
                .addFilters(new CsrfContractFilter(new CsrfTokenContract()))
                .build();
        var csrfResponse = mvc.perform(get("/api/v1/auth/csrf").secure(true))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        var publicResponse = mvc.perform(get("/api/v1/public/links/share-token").secure(true))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        var token = cookieValue(csrfResponse.getHeader("Set-Cookie"));
        var cookies = new Cookie[] {
            new Cookie("ADMIN_SESSION", "admin-id"),
            new Cookie("SIGNER_SESSION", "signer-id"),
            new Cookie("XSRF-TOKEN", token)
        };

        // When / Then
        assertThat(publicResponse.getHeader("Set-Cookie"))
                .startsWith("XSRF-TOKEN=")
                .contains("Path=/", "Secure", "SameSite=Lax")
                .doesNotContain("HttpOnly", "Max-Age");
        mvc.perform(post("/api/v1/admin/probe")
                        .secure(true)
                        .cookie(cookies)
                        .header("X-XSRF-TOKEN", token))
                .andExpect(status().isOk())
                .andExpect(content().string("admin-id"));
        mvc.perform(post("/api/v1/public/probe")
                        .secure(true)
                        .cookie(cookies)
                        .header("X-XSRF-TOKEN", token))
                .andExpect(status().isOk())
                .andExpect(content().string("signer-id"));
        mvc.perform(post("/api/v1/public/probe")
                        .secure(true)
                        .cookie(cookies)
                        .header("Origin", "https://localhost.test"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("csrf_invalid")));
    }

    private static String cookieValue(String setCookie) {
        return setCookie.substring("XSRF-TOKEN=".length(), setCookie.indexOf(';'));
    }

    @RestController
    static final class ProbeController {
        private final PathAwareSessionIdResolver resolver;

        ProbeController(PathAwareSessionIdResolver resolver) {
            this.resolver = resolver;
        }

        @GetMapping("/api/v1/auth/csrf")
        void csrf() {}

        @GetMapping("/api/v1/public/links/{token}")
        void link(@PathVariable String token) {}

        @PostMapping("/api/v1/admin/probe")
        String admin(HttpServletRequest request) {
            return resolver.resolveSessionIds(request).getFirst();
        }

        @PostMapping("/api/v1/public/probe")
        String signer(HttpServletRequest request) {
            return resolver.resolveSessionIds(request).getFirst();
        }
    }
}
