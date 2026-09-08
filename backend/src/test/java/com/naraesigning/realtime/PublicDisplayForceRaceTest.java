package com.naraesigning.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

final class PublicDisplayForceRaceTest {
    private static final UUID BOARD = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void sameCookieReconnectBetweenNotificationAndInvalidationCannotSurvive() throws Exception {
        // Given
        var leases = new PublicDisplayLeaseRegistry(Clock.fixed(
                Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC));
        var realtime = new PublicBoardRealtimeRegistry();
        var owner = leases.claim(BOARD, null).owner();
        var original = mock(SseEmitter.class);
        var reconnect = mock(SseEmitter.class);
        realtime.register(BOARD, () -> leases.owns(BOARD, owner), original);
        var started = new CountDownLatch(1);
        var reconnectTask = new AtomicReference<Future<?>>();

        // When
        try (var executor = Executors.newSingleThreadExecutor()) {
            leases.forceReplace(BOARD, () -> {
                realtime.replace(BOARD);
                reconnectTask.set(executor.submit(() -> {
                    started.countDown();
                    realtime.register(BOARD, () -> leases.owns(BOARD, owner), reconnect);
                }));
                await(started);
            });
            reconnectTask.get().get(1, TimeUnit.SECONDS);
        }
        realtime.publish(BOARD, "probe");

        // Then
        var originalEvents = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(original, times(2)).send(originalEvents.capture());
        verify(reconnect, never()).send(any(SseEmitter.SseEventBuilder.class));
        var terminalEvent = originalEvents.getAllValues().get(1).build().stream()
                .map(data -> data.getData().toString()).collect(java.util.stream.Collectors.joining());
        assertThat(terminalEvent).contains("event:display-replaced", "data:{}");
        assertThat(realtime.connectionCount()).isZero();
    }

    @Test
    void registrationValidatedBeforeForceCannotInstallAfterForce() throws Exception {
        // Given
        var leases = new PublicDisplayLeaseRegistry(Clock.fixed(
                Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC));
        var realtime = new PublicBoardRealtimeRegistry();
        var owner = leases.claim(BOARD, null).owner();
        var checked = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var stale = mock(SseEmitter.class);

        // When
        try (var executor = Executors.newFixedThreadPool(2)) {
            var registration = executor.submit(() -> leases.registerIfOwned(BOARD, owner,
                    () -> realtime.register(BOARD, () -> {
                        var current = leases.owns(BOARD, owner);
                        checked.countDown();
                        await(resume);
                        return current;
                    }, stale)));
            await(checked);
            var forceStarted = new CountDownLatch(1);
            var force = executor.submit(() -> {
                forceStarted.countDown();
                leases.forceReplace(BOARD, () -> realtime.replace(BOARD));
            });
            await(forceStarted);
            resume.countDown();
            registration.get(1, TimeUnit.SECONDS);
            force.get(1, TimeUnit.SECONDS);
        }
        realtime.publish(BOARD, "probe");

        // Then
        var events = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(stale, times(2)).send(events.capture());
        var terminalEvent = events.getAllValues().get(1).build().stream()
                .map(data -> data.getData().toString()).collect(java.util.stream.Collectors.joining());
        assertThat(terminalEvent).contains("event:display-replaced", "data:{}");
        assertThat(realtime.connectionCount()).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
