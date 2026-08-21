package com.naraesigning.board.api;

import com.naraesigning.board.core.BoardOwner;
import com.naraesigning.board.core.BoardUnavailableException;
import com.naraesigning.session.AdminSessionContract;
import com.naraesigning.session.SessionCookieActions;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(Ordered.HIGHEST_PRECEDENCE + 1)
final class AdminBoardFilter extends OncePerRequestFilter {
    static final String OWNER_ATTRIBUTE = AdminBoardFilter.class.getName() + ".owner";
    private static final Pattern BOARD_PATH = Pattern.compile(
            "^/api/v1/admin/boards/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:/.*)?$");
    private static final byte[] UNAUTHORIZED = ("{\"code\":\"UNAUTHORIZED\","
            + "\"message\":\"로그인이 필요합니다.\"}").getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNAVAILABLE = ("{\"code\":\"BOARD_UNAVAILABLE\","
            + "\"message\":\"보드를 찾을 수 없습니다.\"}").getBytes(StandardCharsets.UTF_8);
    private final BoardAdminFacade facade;
    private final SessionCookieActions cookies;
    private final Clock clock;

    AdminBoardFilter(BoardAdminFacade facade, SessionCookieActions cookies, Clock clock) {
        this.facade = facade;
        this.cookies = cookies;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store, private");
        response.setHeader("Referrer-Policy", "no-referrer");
        HttpSession session = request.getSession(false);
        if (session == null || !AdminSessionContract.isCurrent(session, clock.instant())) {
            if (session != null) {
                session.invalidate();
                cookies.logoutAdmin(request, response);
            }
            write(response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED);
            return;
        }
        BoardOwner owner;
        try {
            owner = BoardOwner.fromSession(session);
        } catch (BoardUnavailableException exception) {
            session.invalidate();
            cookies.logoutAdmin(request, response);
            write(response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED);
            return;
        }
        request.setAttribute(OWNER_ATTRIBUTE, owner);
        var matcher = BOARD_PATH.matcher(request.getRequestURI());
        if (matcher.matches()) {
            try {
                facade.authorize(owner, UUID.fromString(matcher.group(1)));
            } catch (BoardUnavailableException exception) {
                write(response, HttpServletResponse.SC_NOT_FOUND, UNAVAILABLE);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(body);
    }
}
