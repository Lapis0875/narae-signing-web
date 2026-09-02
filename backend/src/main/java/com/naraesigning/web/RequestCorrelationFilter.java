package com.naraesigning.web;

import com.naraesigning.session.AdminSessionContract;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    static final String HEADER = "X-Request-ID";
    static final String ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".requestId";
    private static final String ERROR_CODE_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".errorCode";
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final Pattern SAFE_ROUTE = Pattern.compile("/[A-Za-z0-9/_.{}*-]{0,255}");
    private static final String UUID_PART = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
            + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Set<String> METHODS = Set.of("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT");

    public static void errorCode(HttpServletRequest request, String code) {
        if (code != null && SAFE_CODE.matcher(code).matches()) {
            request.setAttribute(ERROR_CODE_ATTRIBUTE, code);
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var supplied = request.getHeader(HEADER);
        var requestId = supplied != null && SAFE_ID.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        long startedAt = System.nanoTime();
        boolean completed = false;
        try (var ignored = MDC.putCloseable("requestId", requestId)) {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            int status = completed ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            log(request, requestId, status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        }
    }

    private static void log(HttpServletRequest request, String requestId, int status, long elapsedMs) {
        var arguments = new Object[] {
            requestId,
            route(request),
            method(request.getMethod()),
            status,
            errorCode(request, status),
            status < 400 ? "success" : "error",
            elapsedMs,
            authCategory(request)
        };
        var message = "requestId={} route={} method={} status={} errorCode={} outcome={} elapsedMs={} authCategory={}";
        if (status >= 500) {
            LOGGER.error(message, arguments);
        } else {
            LOGGER.info(message, arguments);
        }
    }

    private static String route(HttpServletRequest request) {
        var matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (matched instanceof String route && SAFE_ROUTE.matcher(route).matches()) {
            return route;
        }
        var path = request.getRequestURI();
        if (path.matches("/api/v1/auth/(csrf|login|logout|session)")
                || path.equals("/api/v1/admin/boards")
                || path.matches("/api/v1/public/signing-session/(signature|draft(?:/clear)?|cancel)")) {
            return path;
        }
        if (path.matches("/api/v1/public/links/[^/]+(?:/identify)?")) {
            return path.endsWith("/identify")
                    ? "/api/v1/public/links/{token}/identify"
                    : "/api/v1/public/links/{token}";
        }
        if (path.matches("/api/v1/public/links/[^/]+/display/(snapshot|background|events)")) {
            return path.replaceFirst("^(/api/v1/public/links/)[^/]+", "$1{token}");
        }
        var normalized = path
                .replaceFirst("^(/api/v1/admin/boards/)" + UUID_PART, "$1{boardId}")
                .replaceFirst("(/roster/)" + UUID_PART, "$1{entryId}")
                .replaceFirst("(/slots/)" + UUID_PART, "$1{slotId}");
        return !normalized.equals(path) && SAFE_ROUTE.matcher(normalized).matches() ? normalized : "/unmapped";
    }

    private static String method(String method) {
        return method != null && METHODS.contains(method) ? method : "OTHER";
    }

    private static String errorCode(HttpServletRequest request, int status) {
        var supplied = request.getAttribute(ERROR_CODE_ATTRIBUTE);
        if (supplied instanceof String code && SAFE_CODE.matcher(code).matches()) {
            return code;
        }
        var path = request.getRequestURI();
        if (status < 400) return "NONE";
        if (status == 403) return "csrf_invalid";
        if (status == 401 && path.startsWith("/api/v1/admin/")) return "UNAUTHORIZED";
        if (status == 401 && path.equals("/api/v1/auth/login")) return "AUTHENTICATION_FAILED";
        if (status == 400 && path.equals("/api/v1/auth/login")) return "INVALID_CLIENT_IP";
        if (status == 400 && path.contains("/roster")) return "ROSTER_INVALID";
        if (status >= 500) return "INTERNAL_ERROR";
        return "HTTP_" + status;
    }

    private static String authCategory(HttpServletRequest request) {
        try {
            var session = request.getSession(false);
            if (session == null) return "anonymous";
            var names = session.getAttributeNames();
            while (names.hasMoreElements()) {
                var name = names.nextElement();
                if (name.equals(AdminSessionContract.ADMIN_USER_ID) || name.startsWith("signer.")) {
                    return "authenticated";
                }
            }
        } catch (IllegalStateException ignored) {
            return "anonymous";
        }
        return "anonymous";
    }
}
