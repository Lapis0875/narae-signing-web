package com.naraesigning.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

public final class CsrfContractFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final byte[] INVALID_BODY = ("{\"code\":\"csrf_invalid\","
                    + "\"message\":\"요청을 확인해 주세요.\"}")
            .getBytes(StandardCharsets.UTF_8);
    private final CsrfTokenContract tokens;

    public CsrfContractFilter(CsrfTokenContract tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod())) {
            if (materializesToken(request.getRequestURI())) {
                tokens.materialize(request, response);
                response.setHeader("Cache-Control", "no-store, private");
            }
            chain.doFilter(request, response);
            return;
        }
        if (!tokens.matches(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getOutputStream().write(INVALID_BODY);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean materializesToken(String path) {
        return path.equals("/api/v1/auth/csrf")
                || path.matches("/api/v1/public/links/[^/]+");
    }
}
