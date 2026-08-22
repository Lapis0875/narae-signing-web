package com.naraesigning.deletion;

import static org.assertj.core.api.Assertions.assertThat;

import com.naraesigning.background.BackgroundObjectStore;
import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.VersionedCryptoService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BoardDeletionWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void givenTwoPollersWhenLeaseIsCurrentThenSecondCannotClaimBeforeExpiry() {
        // Given
        var fixture = fixture("object-key");
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();

        // When
        var firstClaim = fixture.store.claim(first, NOW, BoardDeletionWorker.LEASE);
        var duplicate = fixture.store.claim(second, NOW.plusSeconds(1), BoardDeletionWorker.LEASE);
        var reclaimed = fixture.store.claim(second, NOW.plus(BoardDeletionWorker.LEASE), BoardDeletionWorker.LEASE);

        // Then
        assertThat(firstClaim).hasSize(1);
        assertThat(duplicate).isEmpty();
        assertThat(reclaimed).singleElement().satisfies(job -> assertThat(job.leaseToken()).isEqualTo(second));
    }

    @Test
    void givenCurrentLeaseWhenRenewedThenReclaimUsesExtendedExpiry() {
        // Given
        var fixture = fixture("object-key");
        var token = UUID.randomUUID();
        var claimed = fixture.store.claim(token, NOW, BoardDeletionWorker.LEASE).getFirst();

        // When
        var renewed = fixture.store.renew(claimed.id(), token, NOW.plusSeconds(30), BoardDeletionWorker.LEASE);
        var earlyReclaim = fixture.store.claim(UUID.randomUUID(), NOW.plus(BoardDeletionWorker.LEASE),
                BoardDeletionWorker.LEASE);

        // Then
        assertThat(renewed).isTrue();
        assertThat(earlyReclaim).isEmpty();
    }

    @Test
    void givenMissingObjectWhenWorkerRunsThenDeleteIsIdempotentAndGraphFinalizes() {
        // Given
        var fixture = fixture("already-missing");

        // When
        fixture.worker().runOnce();

        // Then
        assertThat(fixture.store.completed).isTrue();
        assertThat(fixture.store.finalized).isTrue();
        assertThat(fixture.objects.deleted).containsExactly("already-missing");
    }

    @Test
    void givenTransientFailureAndRestartWhenWorkerRetriesThenJobIsNeverAbandoned() {
        // Given
        var fixture = fixture("retry-object");
        fixture.objects.fail = true;

        // When
        fixture.worker().runOnce();
        fixture.objects.fail = false;
        fixture.store.nextAttemptAt = NOW;
        fixture.worker().runOnce();

        // Then
        assertThat(fixture.store.retryCount).isEqualTo(1);
        assertThat(fixture.store.retryDelay).isLessThanOrEqualTo(Duration.ofHours(1));
        assertThat(fixture.store.completed).isTrue();
        assertThat(fixture.store.finalized).isTrue();
        assertThat(fixture.store.lastError).isEqualTo("OBJECT_DELETE_FAILED");
        assertThat(fixture.store.lastError).doesNotContain("retry-object");
    }

    @Test
    void givenInterruptionAfterObjectDeleteWhenLeaseIsReclaimedThenCompletionIsIdempotent() {
        // Given
        var fixture = fixture("interrupt-object");
        fixture.store.interruptCompletion = true;

        // When
        fixture.worker().runOnce();
        fixture.store.nextAttemptAt = NOW;
        fixture.worker().runOnce();

        // Then
        assertThat(fixture.objects.deleted).containsExactly("interrupt-object", "interrupt-object");
        assertThat(fixture.store.completed).isTrue();
        assertThat(fixture.store.finalized).isTrue();
    }

    private static Fixture fixture(String objectKey) {
        var crypto = new VersionedCryptoService(Map.of(1, new byte[32]), 1);
        var jobId = UUID.randomUUID();
        var encrypted = crypto.encrypt(objectKey.getBytes(StandardCharsets.UTF_8),
                CryptoContext.field("board-deletion-job", jobId.toString(), "object-key"));
        return new Fixture(new LeaseStore(new DeletionJob(jobId, UUID.randomUUID(), encrypted, 0, null, null)),
                new FakeObjects(), crypto);
    }

    private record Fixture(LeaseStore store, FakeObjects objects, VersionedCryptoService crypto) {
        BoardDeletionWorker worker() { return new BoardDeletionWorker(store, objects, crypto, CLOCK); }
    }

    private static final class FakeObjects implements BackgroundObjectStore {
        private final List<String> deleted = new ArrayList<>();
        private boolean fail;
        @Override public byte[] get(String key) { throw new UnsupportedOperationException(); }
        @Override public void put(String key, byte[] bytes) { throw new UnsupportedOperationException(); }
        @Override public void delete(String key) {
            deleted.add(key);
            if (fail) throw new IllegalStateException("synthetic redacted failure");
        }
    }

    private static final class LeaseStore implements BoardDeletionStore {
        private DeletionJob job;
        private Instant nextAttemptAt = NOW;
        private boolean completed;
        private boolean finalized;
        private int retryCount;
        private Duration retryDelay = Duration.ZERO;
        private String lastError;
        private boolean interruptCompletion;

        LeaseStore(DeletionJob job) { this.job = job; }
        @Override public void begin(UUID ownerId, UUID boardId) {}
        @Override public List<DeletionJob> claim(UUID token, Instant now, Duration lease) {
            if (completed || now.isBefore(nextAttemptAt)
                    || (job.leaseExpiresAt() != null && now.isBefore(job.leaseExpiresAt()))) return List.of();
            job = new DeletionJob(job.id(), job.boardId(), job.encryptedObjectKey(), job.attemptCount() + 1,
                    token, now.plus(lease));
            return List.of(job);
        }
        @Override public boolean renew(UUID id, UUID token, Instant now, Duration lease) {
            if (!job.id().equals(id) || !token.equals(job.leaseToken()) || !now.isBefore(job.leaseExpiresAt())) {
                return false;
            }
            job = new DeletionJob(job.id(), job.boardId(), job.encryptedObjectKey(), job.attemptCount(), token,
                    now.plus(lease));
            return true;
        }
        @Override public void complete(UUID id, UUID token) {
            if (interruptCompletion) {
                interruptCompletion = false;
                throw new IllegalStateException("synthetic interruption");
            }
            if (job.id().equals(id) && token.equals(job.leaseToken())) completed = true;
        }
        @Override public void retry(UUID id, UUID token, Instant now, int attempt) {
            if (!job.id().equals(id) || !token.equals(job.leaseToken())) return;
            retryCount++;
            retryDelay = Duration.ofMinutes(Math.min(1L << Math.min(Math.max(attempt - 1, 0), 6), 60));
            nextAttemptAt = now.plus(retryDelay);
            lastError = "OBJECT_DELETE_FAILED";
            job = new DeletionJob(job.id(), job.boardId(), job.encryptedObjectKey(), attempt, null, null);
        }
        @Override public void finalizeReadyBoards() { if (completed) finalized = true; }
    }
}
