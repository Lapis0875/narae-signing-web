package com.naraesigning.web;

import com.google.common.net.InetAddresses;
import com.naraesigning.config.PlatformProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
final class TrustedProxyFilter extends OncePerRequestFilter {
    private static final Set<String> FORWARDED = Set.of(
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-real-ip", "x-narae-client-ip");
    private final PlatformProperties properties;

    TrustedProxyFilter(PlatformProperties properties) { this.properties = properties; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var trusted = request.getRemoteAddr().equals(properties.trustedFrontendIp());
        var clientIps = Collections.list(request.getHeaders("X-Narae-Client-IP"));
        var proto = request.getHeader("X-Forwarded-Proto");
        var invalidClientIp = (!trusted && !clientIps.isEmpty()) || clientIps.size() > 1
                || (clientIps.size() == 1 && !InetAddresses.isInetAddress(clientIps.getFirst()));
        if (invalidClientIp || (trusted && proto != null && !proto.equals("http") && !proto.equals("https"))) {
            RequestCorrelationFilter.errorCode(
                    request, invalidClientIp ? "INVALID_CLIENT_IP" : "INVALID_PROXY_HEADERS");
            response.sendError(400);
            return;
        }
        chain.doFilter(new SanitizedRequest(request, trusted), response);
    }

    private static final class SanitizedRequest extends HttpServletRequestWrapper {
        private final boolean trusted;
        SanitizedRequest(HttpServletRequest request, boolean trusted) { super(request); this.trusted = trusted; }
        @Override public String getHeader(String name) {
            if ("x-forwarded-for".equalsIgnoreCase(name) || "x-real-ip".equalsIgnoreCase(name)) return null;
            if (FORWARDED.contains(name.toLowerCase()) && !trusted) return null;
            return super.getHeader(name);
        }
        @Override public Enumeration<String> getHeaders(String name) {
            return FORWARDED.contains(name.toLowerCase()) && getHeader(name) == null
                    ? Collections.emptyEnumeration() : super.getHeaders(name);
        }
        @Override public String getScheme() {
            return trusted && "https".equals(super.getHeader("X-Forwarded-Proto")) ? "https" : super.getScheme();
        }
        @Override public boolean isSecure() { return "https".equals(getScheme()); }
        @Override public int getServerPort() { return isSecure() ? 443 : super.getServerPort(); }
    }
}
