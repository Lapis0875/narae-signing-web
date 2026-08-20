package com.naraesigning.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class CsrfTokenContract {
    public static final String COOKIE_NAME = "XSRF-TOKEN";
    public static final String HEADER_NAME = "X-XSRF-TOKEN";
    private final SecureRandom random = new SecureRandom();

    public void materialize(HttpServletRequest request, HttpServletResponse response) {
        if (cookieValue(request) == null) {
            rotate(response);
        }
    }

    public void rotate(HttpServletResponse response) {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        response.addHeader("Set-Cookie", cookie(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)));
    }

    public void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", "XSRF-TOKEN=; Path=/; Max-Age=0; Secure; SameSite=Lax");
    }

    public boolean matches(HttpServletRequest request) {
        var cookie = cookieValue(request);
        var header = request.getHeader(HEADER_NAME);
        return cookie != null && header != null && !cookie.isEmpty() && !header.isEmpty() && MessageDigest.isEqual(
                cookie.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
    }

    private static String cookieValue(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static String cookie(String value) {
        return COOKIE_NAME + "=" + value + "; Path=/; Secure; SameSite=Lax";
    }
}
