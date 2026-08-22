package com.naraesigning.realtime;

import com.naraesigning.session.AdminSessionContract;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
final class BoardRealtimeRegistry {
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Connection> connections = new ConcurrentHashMap<>();
    private final AtomicLong eventIds = new AtomicLong();

    BoardRealtimeRegistry() { this(Clock.systemUTC()); }
    BoardRealtimeRegistry(Clock clock) { this.clock = clock; }

    boolean register(UUID boardId, HttpSession session, SseEmitter emitter) {
        if (!AdminSessionContract.isCurrent(session, clock.instant())) {
            emitter.complete();
            return false;
        }
        var connectionId = UUID.randomUUID();
        connections.put(connectionId, new Connection(boardId, session, emitter));
        emitter.onCompletion(() -> connections.remove(connectionId));
        emitter.onTimeout(() -> connections.remove(connectionId));
        emitter.onError(error -> connections.remove(connectionId));
        return true;
    }

    void publish(UUID boardId, String type) {
        var eventId = eventIds.incrementAndGet();
        connections.forEach((connectionId, connection) -> {
            if (connection.boardId().equals(boardId)) send(connectionId, connection, eventId, type);
        });
    }

    @Scheduled(fixedDelay = 15_000)
    void heartbeat() {
        connections.forEach((connectionId, connection) -> {
            if (!current(connection)) {
                connections.remove(connectionId);
                connection.emitter().complete();
                return;
            }
            try {
                connection.emitter().send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException exception) {
                connections.remove(connectionId);
                connection.emitter().complete();
            }
        });
    }

    int connectionCount() { return connections.size(); }

    private void send(UUID connectionId, Connection connection, long eventId, String type) {
        if (!current(connection)) {
            connections.remove(connectionId);
            connection.emitter().complete();
            return;
        }
        try {
            connection.emitter().send(SseEmitter.event()
                    .id(Long.toString(eventId))
                    .name(type));
        } catch (IOException | IllegalStateException exception) {
            connections.remove(connectionId);
            connection.emitter().complete();
        }
    }

    private boolean current(Connection connection) {
        try {
            return AdminSessionContract.isCurrent(connection.session(), clock.instant());
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private record Connection(UUID boardId, HttpSession session, SseEmitter emitter) {}
}
