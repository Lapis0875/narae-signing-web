package com.naraesigning.render;

import com.naraesigning.session.AdminSessionContract;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/boards")
@ConditionalOnProperty("spring.datasource.url")
final class FinalPngController {
    private final FinalPngService service;
    private final Clock clock;

    FinalPngController(FinalPngService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping(path = "/{boardId}/final.png", produces = "image/png")
    void download(@PathVariable UUID boardId, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Disposition", "attachment; filename=board-final.png");
        response.setContentType("image/png");
        var session = request.getSession(false);
        if (session == null || !AdminSessionContract.isCurrent(session, clock.instant())) {
            if (session != null) session.invalidate();
            throw FinalPngException.unauthorized();
        }
        UUID ownerId;
        try {
            ownerId = UUID.fromString(String.valueOf(
                    session.getAttribute(AdminSessionContract.ADMIN_USER_ID)));
        } catch (RuntimeException exception) {
            session.invalidate();
            throw FinalPngException.unauthorized();
        }
        try {
            service.render(ownerId, boardId, response.getOutputStream());
        } catch (IOException exception) {
            throw FinalPngException.unavailable();
        }
    }
}
