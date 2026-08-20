package com.naraesigning.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.naraesigning.config.PlatformProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

final class TrustedProxyFilterTest {
    private final TrustedProxyFilter filter = new TrustedProxyFilter(new PlatformProperties(
            "http://localhost", "http://minio", "bucket", "access", "secret", "/key", 1, "172.30.0.10"));

    @Test
    void forgedClientIpIsRejected_whenPeerIsUntrusted() throws Exception {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.8");
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        request.addHeader("X-Narae-Client-IP", "198.51.100.2");
        request.addHeader("X-Forwarded-Proto", "https");
        var response = new MockHttpServletResponse();
        var entered = new AtomicReference<>(false);

        filter.doFilter(request, response, (wrapped, ignored) -> entered.set(true));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(entered).hasValue(false);
    }

    @Test
    void clientIpSurvives_whenFrontendIsTrusted() throws Exception {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("172.30.0.10");
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        request.addHeader("X-Narae-Client-IP", "198.51.100.2");
        request.addHeader("X-Forwarded-Proto", "https");
        var response = new MockHttpServletResponse();
        var observed = new AtomicReference<String>();

        filter.doFilter(request, response, (wrapped, ignored) -> {
            var http = (HttpServletRequest) wrapped;
            observed.set(http.getHeader("X-Forwarded-For") + ":" + http.getHeader("X-Narae-Client-IP")
                    + ":" + http.getScheme() + ":" + http.isSecure());
        });

        assertThat(observed).hasValue("null:198.51.100.2:https:true");
    }

    @Test
    void malformedClientIpIsRejected_whenFrontendIsTrusted() throws Exception {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("172.30.0.10");
        request.addHeader("X-Narae-Client-IP", "198.51.100.2, 203.0.113.4");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (wrapped, ignored) -> {});

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void forwardedProtoIsIgnored_whenPeerIsUntrusted() throws Exception {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.8");
        request.addHeader("X-Forwarded-Proto", "https");
        var response = new MockHttpServletResponse();
        var observed = new AtomicReference<String>();

        filter.doFilter(request, response, (wrapped, ignored) -> {
            var http = (HttpServletRequest) wrapped;
            observed.set(http.getHeader("X-Forwarded-Proto") + ":" + http.getScheme());
        });

        assertThat(observed).hasValue("null:http");
    }
}
