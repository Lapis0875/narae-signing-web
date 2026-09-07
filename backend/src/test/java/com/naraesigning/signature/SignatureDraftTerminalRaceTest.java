package com.naraesigning.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.realtime.LiveSignatureRegistry;
import com.naraesigning.session.SignerSessionContract;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class SignatureDraftTerminalRaceTest {
    private static final UUID BOARD_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SLOT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ROSTER_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID CLAIM_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    @Test
    void delayedFullPutCannotRestoreDraftAfterCancelCompletes() throws Exception {
        // Given
        var repository = new PausingRepository();
        var registry = registry();
        var service = new LiveSignatureService(repository, registry);
        repository.pauseAfterAuthorization();

        // When
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var delayedPut = workers.submit(() -> {
                try {
                    service.update(session(), CLAIM_ID, payload(3, 4), NOW.plusSeconds(1));
                    return null;
                } catch (SignatureSubmitException exception) {
                    return exception;
                }
            });
            assertThat(repository.authorized.await(5, TimeUnit.SECONDS)).isTrue();
            service.cancel(session(), CLAIM_ID);
            assertThat(registry.snapshot(BOARD_ID, SLOT_ID).signature()).isNull();
            repository.resume.countDown();

            // Then
            assertThat(delayedPut.get())
                    .isNotNull()
                    .extracting(SignatureSubmitException::code)
                    .isEqualTo("signature_draft_out_of_sync");
            assertThat(registry.snapshot(BOARD_ID, SLOT_ID).signature()).isNull();
            System.out.println("RACE cancel delayedPut=signature_draft_out_of_sync finalDraft=absent");
        } finally {
            repository.resume.countDown();
        }
    }

    private static LiveSignatureRegistry registry() throws Exception {
        var constructor = LiveSignatureRegistry.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var parameterTypes = constructor.getParameterTypes();
        return (LiveSignatureRegistry) constructor.newInstance(
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                mock(parameterTypes[2]), mock(parameterTypes[3]));
    }

    private static SignatureSession session() {
        return new SignatureSession(new SignerSessionContract.Value(
                BOARD_ID, SLOT_ID, 3, 11, 1.5, NOW.minusSeconds(60)), true);
    }

    private static SignaturePayload payload(int x, int y) {
        return new SignaturePayload(
                ("{\"version\":1,\"strokes\":[{\"points\":[{\"x\":" + x + ",\"y\":" + y + "}]}]}")
                        .getBytes(StandardCharsets.UTF_8),
                java.util.List.of());
    }

    private static final class PausingRepository implements SignatureSubmissionRepository {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicBoolean pause = new AtomicBoolean();
        private final CountDownLatch authorized = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private LockedState state = new LockedState(
                BOARD_ID, SLOT_ID, ROSTER_ID, "OPEN", 3, "PLACED", 11, 1.5,
                false, false, false, false, null, CLAIM_ID, NOW.plusSeconds(90));

        private void pauseAfterAuthorization() {
            pause.set(true);
        }

        @Override
        public <T> T withBoardThenSlotLocked(UUID boardId, UUID slotId, LockedAction<T> action) {
            var renewed = new boolean[1];
            T result;
            lock.lock();
            try {
                result = action.apply(state, new SubmissionWriter() {
                    @Override
                    public void save(com.naraesigning.crypto.EncryptedValue encrypted, Instant submittedAt) {}

                    @Override
                    public void renewClaim(UUID claimId, Instant expiresAt) {
                        renewed[0] = true;
                        state = withClaim(state, claimId, expiresAt);
                    }

                    @Override
                    public void releaseClaim(UUID claimId) {
                        if (claimId.equals(state.activeSignerClaim())) state = withClaim(state, null, null);
                    }
                });
            } finally {
                lock.unlock();
            }
            if (renewed[0] && pause.compareAndSet(true, false)) {
                authorized.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("PUT did not resume");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            return result;
        }

        private static LockedState withClaim(LockedState state, UUID claimId, Instant expiresAt) {
            return new LockedState(
                    state.boardId(), state.slotId(), state.rosterEntryId(), state.boardStatus(),
                    state.linkVersion(), state.placementStatus(), state.revision(), state.aspect(),
                    state.rosterSubmitted(), state.ciphertextPresent(), state.noncePresent(),
                    state.keyVersionPresent(), state.submittedAt(), claimId, expiresAt);
        }
    }
}
