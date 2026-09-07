package com.naraesigning.realtime;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class LiveSignatureRegistryLockOrderTest {
    private static final UUID BOARD = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SLOT = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID CLAIM = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void cacheMissAuthorizationDoesNotDeadlockBoardFence() throws Exception {
        // Given
        var registry = new LiveSignatureRegistry(new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                mock(BoardRealtimeRegistry.class), mock(PublicBoardRealtimeRegistry.class));
        var boardLock = new ReentrantLock();
        var closeHasBoardLock = new CountDownLatch(1);
        var authorizeEntered = new CountDownLatch(1);
        var closeMayFence = new CountDownLatch(1);
        var deltaFinished = new CountDownLatch(1);

        // When
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var close = workers.submit(() -> {
                boardLock.lock();
                try {
                    closeHasBoardLock.countDown();
                    await(closeMayFence);
                    registry.fenceBoard(BOARD);
                } finally {
                    boardLock.unlock();
                }
                await(deltaFinished);
                registry.invalidateBoard(BOARD);
                registry.unfenceBoard(BOARD);
                return null;
            });
            var delta = workers.submit(() -> {
                await(closeHasBoardLock);
                try {
                    registry.apply(BOARD, SLOT, CLAIM,
                            new DraftDelta(DraftDelta.Operation.BEGIN, 1, 0, 0, 0,
                                    List.of(new DraftDelta.Point(1, 2))),
                            () -> authorize(authorizeEntered, boardLock));
                    return false;
                } catch (LiveSignatureRegistry.OutOfSyncException exception) {
                    return true;
                } finally {
                    deltaFinished.countDown();
                }
            });
            assertThat(authorizeEntered.await(2, SECONDS)).isTrue();
            closeMayFence.countDown();

            // Then
            try {
                assertThat(delta.get(2, SECONDS)).isTrue();
                close.get(2, SECONDS);
            } finally {
                delta.cancel(true);
                close.cancel(true);
                deltaFinished.countDown();
                closeMayFence.countDown();
            }
        }
    }

    private static Instant authorize(CountDownLatch entered, ReentrantLock boardLock) {
        entered.countDown();
        try {
            boardLock.lockInterruptibly();
            try {
                return NOW.plusSeconds(90);
            } finally {
                boardLock.unlock();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, SECONDS)) throw new AssertionError("latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
