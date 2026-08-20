package com.naraesigning.session;

import com.naraesigning.security.CsrfTokenContract;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class SessionCookieActions {
    private final PathAwareSessionIdResolver sessions;
    private final CsrfTokenContract csrf;

    public SessionCookieActions(PathAwareSessionIdResolver sessions, CsrfTokenContract csrf) {
        this.sessions = sessions;
        this.csrf = csrf;
    }

    public void adminAuthenticated(HttpServletRequest request, HttpServletResponse response) {
        request.getSession(true);
        request.changeSessionId();
        csrf.rotate(response);
    }

    public void signerIdentified(HttpServletRequest request, HttpServletResponse response) {
        request.getSession(true);
        request.changeSessionId();
        csrf.rotate(response);
    }

    public void logoutAdmin(HttpServletRequest request, HttpServletResponse response) {
        sessions.expireSession(request, response);
        csrf.clear(response);
    }

    public void clearStaleSigner(HttpServletRequest request, HttpServletResponse response) {
        sessions.expireSession(request, response);
    }
}
