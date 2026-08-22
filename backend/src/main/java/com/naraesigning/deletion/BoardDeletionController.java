package com.naraesigning.deletion;

import com.naraesigning.session.AdminSessionContract;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/boards")
@ConditionalOnProperty("spring.datasource.url")
final class BoardDeletionController {
    private final BoardDeletionService service;
    private final Clock clock;

    BoardDeletionController(BoardDeletionService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @DeleteMapping("/{boardId}")
    ResponseEntity<Void> delete(@PathVariable UUID boardId, @RequestBody Confirmation body,
            HttpServletRequest request) {
        var session = request.getSession(false);
        if (!body.confirmed() || session == null || !AdminSessionContract.isCurrent(session, clock.instant())) {
            throw new BoardDeletionException(body.confirmed() ? "UNAUTHORIZED" : "DELETION_CONFIRMATION_REQUIRED");
        }
        UUID ownerId;
        try {
            ownerId = UUID.fromString(session.getAttribute(AdminSessionContract.ADMIN_USER_ID).toString());
        } catch (RuntimeException exception) {
            throw new BoardDeletionException("UNAUTHORIZED");
        }
        service.delete(ownerId, boardId);
        return ResponseEntity.noContent().build();
    }

    record Confirmation(boolean confirmed) {}
}
