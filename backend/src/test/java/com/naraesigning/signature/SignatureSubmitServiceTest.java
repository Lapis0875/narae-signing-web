package com.naraesigning.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.EncryptedValue;
import com.naraesigning.crypto.VersionedCryptoService;
import com.naraesigning.session.SignerSessionContract;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class SignatureSubmitServiceTest {
    private static final UUID BOARD_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SLOT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ROSTER_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-08-21T00:10:00Z");
    private static final byte[] JSON = ("{\"version\":1,\"strokes\":[{\"points\":["
            + "{\"x\":0,\"y\":1000000},{\"x\":500000,\"y\":250000}]}]}")
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void encryptsWithExactSlotBoundAadAndSchedulesOneEvent() {
        // Given
        var repository = new MemoryRepository(current());
        var events = new MemoryEvents(repository);
        var crypto = crypto();
        var service = new SignatureSubmitService(repository, crypto, events);

        // When
        service.submit(session(), new SignaturePayload(JSON, java.util.List.of()), NOW);

        // Then
        assertThat(repository.saved).isNotNull();
        assertThat(crypto.decrypt(repository.saved,
                CryptoContext.field("signature-slot", SLOT_ID.toString(), "strokes")))
                .isEqualTo(JSON);
        assertThat(events.afterCommitCount).isEqualTo(1);
    }

    @Test
    void twoBarrierControlledSubmissionsAreFirstWins() throws Exception {
        // Given
        var repository = new MemoryRepository(current());
        var events = new MemoryEvents(repository);
        var service = new SignatureSubmitService(repository, crypto(), events);
        var start = new CountDownLatch(1);
        var successes = new AtomicInteger();
        var conflicts = new AtomicInteger();

        // When
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = java.util.stream.IntStream.range(0, 2).mapToObj(index -> workers.submit(() -> {
                start.await();
                try {
                    service.submit(session(), payload(index), NOW);
                    successes.incrementAndGet();
                } catch (SignatureSubmitException exception) {
                    if (exception.code().equals("signature_already_submitted")) conflicts.incrementAndGet();
                }
                return null;
            })).toList();
            start.countDown();
            for (var call : calls) call.get();
        }

        // Then
        assertThat(successes).hasValue(1);
        assertThat(conflicts).hasValue(1);
        assertThat(repository.saveCount).isEqualTo(1);
        assertThat(events.afterCommitCount).isEqualTo(1);
    }

    @Test
    void failsClosedForEveryStaleClosedDeletingExpiredAndSplitState() {
        // Given / When / Then
        assertRejected(current().withStatus("CLOSED"), session(), "board_closed");
        assertRejected(current().withStatus("DELETING"), session(), "signer_stale");
        assertRejected(current().withStatus("DRAFT"), session(), "signer_stale");
        assertRejected(current().withStatus("UNAVAILABLE"), session(), "signer_stale");
        assertRejected(current().withLinkVersion(4), session(), "signer_stale");
        assertRejected(current().withRevision(12), session(), "signer_stale");
        assertRejected(current().withAspect(2.0), session(), "signer_stale");
        assertRejected(current().withPlacement("UNPLACED"), session(), "signer_stale");
        assertRejected(current().withRosterSubmitted(true), session(), "signature_state_invalid");
        assertRejected(current().withCiphertextState(true, true, true, NOW), session(),
                "signature_state_invalid");
        assertRejected(current(), new SignatureSession(session().value(), false), "signer_session_expired");
    }

    private static void assertRejected(
            SignatureSubmissionRepository.LockedState state,
            SignatureSession session,
            String code) {
        var repository = new MemoryRepository(state);
        var events = new MemoryEvents(repository);
        var service = new SignatureSubmitService(repository, crypto(), events);

        assertThatThrownBy(() -> service.submit(session, payload(0), NOW))
                .isInstanceOfSatisfying(SignatureSubmitException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
        assertThat(repository.saveCount).isZero();
        assertThat(events.afterCommitCount).isZero();
    }

    private static SignatureSubmissionRepository.LockedState current() {
        return new SignatureSubmissionRepository.LockedState(
                BOARD_ID, SLOT_ID, ROSTER_ID, "OPEN", 3, "PLACED", 11, 1.5,
                false, false, false, false, null);
    }

    private static SignatureSession session() {
        return new SignatureSession(new SignerSessionContract.Value(
                BOARD_ID, SLOT_ID, 3, 11, 1.5, NOW.minusSeconds(60)), true);
    }

    private static SignaturePayload payload(int point) {
        var json = ("{\"version\":1,\"strokes\":[{\"points\":[{\"x\":" + point
                + ",\"y\":1}]}]}").getBytes(StandardCharsets.UTF_8);
        return new SignaturePayload(json, java.util.List.of());
    }

    private static VersionedCryptoService crypto() {
        return new VersionedCryptoService(Map.of(7, new byte[32]), 7);
    }

    private static final class MemoryRepository implements SignatureSubmissionRepository {
        private final ReentrantLock lock = new ReentrantLock();
        private LockedState state;
        private EncryptedValue saved;
        private int saveCount;

        private MemoryRepository(LockedState state) {
            this.state = state;
        }

        @Override
        public <T> T withBoardThenSlotLocked(UUID boardId, UUID slotId, LockedAction<T> action) {
            lock.lock();
            try {
                var result = action.apply(state, (encrypted, submittedAt) -> {
                    saved = encrypted;
                    saveCount++;
                    state = state.withCiphertextState(true, true, true, submittedAt)
                            .withRosterSubmitted(true);
                });
                return result;
            } finally {
                lock.unlock();
            }
        }
    }

    private static final class MemoryEvents implements SignatureSubmissionEvents {
        private final MemoryRepository repository;
        private int afterCommitCount;

        private MemoryEvents(MemoryRepository repository) {
            this.repository = repository;
        }

        @Override
        public void afterCommit(SignatureSubmitted event) {
            afterCommitCount++;
            assertThat(event.boardId()).isEqualTo(repository.state.boardId());
        }
    }
}
