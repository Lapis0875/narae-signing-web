package com.naraesigning.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
final class BodyLimitFilter extends OncePerRequestFilter {
    private static final long DEFAULT_LIMIT = 65_536;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var limit = limit(request.getMethod(), request.getRequestURI());
        if (limit < 0) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > limit) {
            reject(response);
            return;
        }
        HttpServletRequest guarded = new LimitedRequest(request, limit);
        BufferedMultipartRequest multipart = null;
        if (request.getContentType() != null && request.getContentType().startsWith("multipart/form-data")) {
            var body = request.getInputStream().readNBytes(Math.toIntExact(limit + 1));
            if (body.length > limit) {
                reject(response);
                return;
            }
            multipart = new BufferedMultipartRequest(request, body);
            guarded = multipart;
        }
        try {
            chain.doFilter(guarded, response);
        } catch (RequestTooLargeException exception) {
            if (!response.isCommitted()) {
                response.reset();
                reject(response);
            }
        } finally {
            if (multipart != null) multipart.cleanup();
        }
    }

    private static long limit(String method, String path) {
        if ("POST".equals(method) && path.matches("/api/v1/admin/boards/[^/]+/background")) return 52_500_000;
        if ("POST".equals(method) && path.matches("/api/v1/admin/boards/[^/]+/roster/import")) return 1_065_000;
        if ("PUT".equals(method) && path.matches("/api/v1/admin/boards/[^/]+/roster")) return 1_048_576;
        if ("POST".equals(method) && path.equals("/api/v1/public/signing-session/signature")) return 1_048_576;
        if ("POST".equals(method) && (path.equals("/api/v1/auth/login")
                || path.matches("/api/v1/public/links/[^/]+/identify"))) return 16_384;
        return path.startsWith("/api/") && !"GET".equals(method) && !"HEAD".equals(method) ? DEFAULT_LIMIT : -1;
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setHeader("Content-Type", "application/json");
        response.getOutputStream().write("{\"code\":\"request_too_large\"}".getBytes(StandardCharsets.UTF_8));
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long limit;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedInputStream(super.getInputStream(), limit);
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private long read;

        LimitedInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override public int read() throws IOException {
            var value = delegate.read();
            if (value >= 0 && ++read > limit) throw new RequestTooLargeException();
            return value;
        }
        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
    }
}
