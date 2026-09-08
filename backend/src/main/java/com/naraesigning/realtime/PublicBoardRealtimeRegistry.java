package com.naraesigning.realtime;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public final class PublicBoardRealtimeRegistry {
    private final ConcurrentHashMap<UUID, Connection> connections = new ConcurrentHashMap<>();

    void register(UUID boardId, BooleanSupplier current, SseEmitter emitter) {
        if (!current(current)) {
            emitter.complete();
            return;
        }
        var connection = new Connection(boardId, current, emitter, new AtomicLong());
        var previous = connections.put(boardId, connection);
        if (previous != null) previous.emitter().complete();
        emitter.onCompletion(() -> connections.remove(boardId, connection));
        emitter.onTimeout(() -> connections.remove(boardId, connection));
        emitter.onError(error -> connections.remove(boardId, connection));
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException exception) {
            remove(boardId, connection);
        }
    }

    void publish(UUID boardId, String type) {
        publish(boardId, type, "{}");
    }

    void publish(UUID boardId, String type, Object data) {
        var connection = connections.get(boardId);
        if (connection != null) send(boardId, connection, connection.eventIds().incrementAndGet(), type, data);
    }

    public void replace(UUID boardId) {
        var connection = connections.get(boardId);
        if (connection == null) return;
        send(boardId, connection, connection.eventIds().incrementAndGet(), "display-replaced", "{}");
        remove(boardId, connection);
    }

    @Scheduled(fixedDelay = 15_000)
    void heartbeat() {
        connections.forEach((boardId, connection) -> {
            if (!current(connection.current())) {
                remove(boardId, connection);
                return;
            }
            try {
                connection.emitter().send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException exception) {
                remove(boardId, connection);
            }
        });
    }

    int connectionCount() { return connections.size(); }

    private void send(UUID boardId, Connection connection, long eventId, String type, Object data) {
        if (!current(connection.current())) {
            remove(boardId, connection);
            return;
        }
        try {
            connection.emitter().send(SseEmitter.event().id(Long.toString(eventId)).name(type).data(data));
        } catch (IOException | IllegalStateException exception) {
            remove(boardId, connection);
        }
    }

    private static boolean current(BooleanSupplier check) {
        try {
            return check.getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void remove(UUID boardId, Connection connection) {
        connections.remove(boardId, connection);
        connection.emitter().complete();
    }

    private record Connection(
            UUID boardId, BooleanSupplier current, SseEmitter emitter, AtomicLong eventIds) {}
}
