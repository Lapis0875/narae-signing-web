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
final class PublicBoardRealtimeRegistry {
    private final ConcurrentHashMap<UUID, Connection> connections = new ConcurrentHashMap<>();
    private final AtomicLong eventIds = new AtomicLong();

    void register(UUID boardId, BooleanSupplier current, SseEmitter emitter) {
        if (!current(current)) {
            emitter.complete();
            return;
        }
        var connectionId = UUID.randomUUID();
        var connection = new Connection(boardId, current, emitter);
        connections.put(connectionId, connection);
        emitter.onCompletion(() -> connections.remove(connectionId));
        emitter.onTimeout(() -> connections.remove(connectionId));
        emitter.onError(error -> connections.remove(connectionId));
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException exception) {
            remove(connectionId, connection);
        }
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
            if (!current(connection.current())) {
                remove(connectionId, connection);
                return;
            }
            try {
                connection.emitter().send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException exception) {
                remove(connectionId, connection);
            }
        });
    }

    private void send(UUID connectionId, Connection connection, long eventId, String type) {
        if (!current(connection.current())) {
            remove(connectionId, connection);
            return;
        }
        try {
            connection.emitter().send(SseEmitter.event().id(Long.toString(eventId)).name(type).data("{}"));
        } catch (IOException | IllegalStateException exception) {
            remove(connectionId, connection);
        }
    }

    private static boolean current(BooleanSupplier check) {
        try {
            return check.getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void remove(UUID connectionId, Connection connection) {
        connections.remove(connectionId);
        connection.emitter().complete();
    }

    private record Connection(UUID boardId, BooleanSupplier current, SseEmitter emitter) {}
}
