package com.naraesigning.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class BoardRealtimeRegistryTest {
    private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");

    @Test
    void refusesRegistrationWhenAbsoluteAdminSessionExpired() {
        // Given
        var registry = new BoardRealtimeRegistry(Clock.fixed(NOW, ZoneOffset.UTC));
        var session = session(NOW.minusSeconds(43_201));
        var emitter = mock(SseEmitter.class);

        // When
        var registered = registry.register(UUID.randomUUID(), session, emitter);

        // Then
        assertThat(registered).isFalse();
        assertThat(registry.connectionCount()).isZero();
        verify(emitter).complete();
    }

    @Test
    void publishesOnlyMinimalEventToTheMatchingOwnerBoard() throws Exception {
        // Given
        var registry = new BoardRealtimeRegistry(Clock.fixed(NOW, ZoneOffset.UTC));
        var board = UUID.randomUUID();
        var otherBoard = UUID.randomUUID();
        var matching = mock(SseEmitter.class);
        var isolated = mock(SseEmitter.class);
        registry.register(board, session(NOW), matching);
        registry.register(otherBoard, session(NOW), isolated);

        // When
        registry.publish(board, "signature-submitted");

        // Then
        verify(matching).send(any(SseEmitter.SseEventBuilder.class));
        verify(isolated, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void heartbeatRemovesAnExpiredConnection() {
        // Given
        var mutableClock = new MutableClock(NOW);
        var registry = new BoardRealtimeRegistry(mutableClock);
        var emitter = mock(SseEmitter.class);
        registry.register(UUID.randomUUID(), session(NOW), emitter);
        mutableClock.now = NOW.plusSeconds(43_201);

        // When
        registry.heartbeat();

        // Then
        assertThat(registry.connectionCount()).isZero();
        verify(emitter).complete();
    }

    @Test
    void completionCallbackRemovesTheConnection() {
        // Given
        var registry = new BoardRealtimeRegistry(Clock.fixed(NOW, ZoneOffset.UTC));
        var emitter = mock(SseEmitter.class);
        var completion = new AtomicReference<Runnable>();
        org.mockito.Mockito.doAnswer(invocation -> {
            completion.set(invocation.getArgument(0));
            return null;
        }).when(emitter).onCompletion(any());
        registry.register(UUID.randomUUID(), session(NOW), emitter);

        // When
        completion.get().run();

        // Then
        assertThat(registry.connectionCount()).isZero();
    }

    private static MockHttpSession session(Instant issuedAt) {
        var session = new MockHttpSession();
        session.setAttribute("admin.issuedAt", issuedAt);
        session.setAttribute("org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME",
                UUID.randomUUID().toString());
        return session;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) { this.now = now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
