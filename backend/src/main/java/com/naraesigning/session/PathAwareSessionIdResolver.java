package com.naraesigning.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.HttpSessionIdResolver;

public final class PathAwareSessionIdResolver implements HttpSessionIdResolver {
    private final HttpSessionIdResolver admin = resolver("ADMIN_SESSION", "/api/v1");
    private final HttpSessionIdResolver signer = resolver("SIGNER_SESSION", "/api/v1/public");

    @Override
    public List<String> resolveSessionIds(HttpServletRequest request) {
        return resolver(request).resolveSessionIds(request);
    }

    @Override
    public void setSessionId(HttpServletRequest request, HttpServletResponse response, String sessionId) {
        resolver(request).setSessionId(request, response, sessionId);
    }

    @Override
    public void expireSession(HttpServletRequest request, HttpServletResponse response) {
        resolver(request).expireSession(request, response);
    }

    private HttpSessionIdResolver resolver(HttpServletRequest request) {
        var path = request.getRequestURI();
        return path.equals("/api/v1/public") || path.startsWith("/api/v1/public/") ? signer : admin;
    }

    private static HttpSessionIdResolver resolver(String name, String path) {
        var serializer = new DefaultCookieSerializer();
        serializer.setCookieName(name);
        serializer.setCookiePath(path);
        serializer.setUseSecureCookie(true);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseBase64Encoding(false);
        var resolver = new CookieHttpSessionIdResolver();
        resolver.setCookieSerializer(serializer);
        return resolver;
    }
}
