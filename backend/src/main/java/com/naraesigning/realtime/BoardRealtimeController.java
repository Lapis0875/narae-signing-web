package com.naraesigning.realtime;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/admin/boards/{boardId}")
@ConditionalOnProperty("spring.datasource.url")
final class BoardRealtimeController {
    private final BoardRealtimeRegistry registry;
    private final ObjectProvider<BoardSnapshotService> snapshots;

    BoardRealtimeController(BoardRealtimeRegistry registry, ObjectProvider<BoardSnapshotService> snapshots) {
        this.registry = registry;
        this.snapshots = snapshots;
    }

    @GetMapping("/events")
    SseEmitter events(@PathVariable UUID boardId, HttpServletRequest request) {
        var emitter = new SseEmitter(0L);
        registry.register(boardId, request.getSession(false), emitter);
        return emitter;
    }

    @GetMapping("/snapshot")
    BoardSnapshot snapshot(@PathVariable UUID boardId, HttpServletRequest request) {
        return snapshots.getObject().read(boardId, UUID.fromString(request.getSession(false)
                .getAttribute("org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME")
                .toString()));
    }
}
