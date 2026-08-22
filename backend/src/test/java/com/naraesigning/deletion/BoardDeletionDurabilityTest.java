package com.naraesigning.deletion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoardDeletionDurabilityTest {
    @Test
    void givenTwoObjectsWhenOnlyOneSucceedsThenPhaseBWaitsForEveryPersistedJob() {
        // Given
        var fixture = PersistedDeletionFixture.create("first-object", "second-object");
        fixture.objects().failNext("second-object", 1);

        // When
        fixture.worker().runOnce();

        // Then
        assertThat(fixture.store().graphRowCount()).isPositive();
        assertThat(fixture.store().jobRowCount()).isEqualTo(2);
        assertThat(fixture.store().status("first-object")).isEqualTo("COMPLETED");
        assertThat(fixture.store().status("second-object")).isEqualTo("PENDING");

        // When
        fixture.clock().advance(Duration.ofMinutes(1));
        fixture.newWorker().runOnce();

        // Then
        assertThat(fixture.store().graphRowCount()).isZero();
        assertThat(fixture.store().jobRowCount()).isZero();
    }

    @Test
    void givenOutageAndPollerCrashWhenLeaseExpiresThenRestartReclaimsWithoutAbandonment() {
        // Given
        var fixture = PersistedDeletionFixture.create("retry-object", "stable-object");
        fixture.objects().failNext("retry-object", 2);
        var access = DeletionAccessProbe.forState(fixture.store());

        // When
        fixture.worker().runOnce();

        // Then
        access.assertDenied();
        assertThat(fixture.objects().deletes("retry-object")).isEqualTo(1);
        assertThat(fixture.store().attemptCount("retry-object")).isEqualTo(1);

        // When
        fixture.clock().advance(Duration.ofMinutes(1));
        fixture.newWorker().runOnce();
        var persistedRetryAt = fixture.store().nextAttemptAt("retry-object");
        var restartedWorker = fixture.newWorker();

        // Then
        access.assertDenied();
        assertThat(fixture.objects().deletes("retry-object")).isEqualTo(2);
        assertThat(fixture.store().attemptCount("retry-object")).isEqualTo(2);
        assertThat(fixture.store().nextAttemptAt("retry-object")).isEqualTo(persistedRetryAt);
        assertThat(persistedRetryAt).isAfter(fixture.clock().instant());
        assertThat(Duration.between(fixture.clock().instant(), persistedRetryAt))
                .isLessThanOrEqualTo(Duration.ofHours(1));
        assertThat(fixture.store().lastError("retry-object")).isEqualTo("OBJECT_DELETE_FAILED");
        assertThat(fixture.store().lastError("retry-object")).doesNotContain("retry-object");

        // When
        fixture.clock().advance(Duration.ofMinutes(2));
        fixture.store().crashPollerAfterClaim(fixture.clock().instant());
        var persistedLeaseExpiry = fixture.store().leaseExpiresAt("retry-object");

        // Then
        access.assertDenied();

        // When
        fixture.clock().advance(Duration.ofSeconds(1));
        fixture.newWorker().runOnce();

        // Then
        access.assertDenied();
        assertThat(fixture.store().attemptCount("retry-object")).isEqualTo(3);
        assertThat(fixture.objects().deletes("retry-object")).isEqualTo(2);
        assertThat(fixture.store().leaseExpiresAt("retry-object")).isEqualTo(persistedLeaseExpiry);
        assertThat(fixture.clock().instant()).isBefore(persistedLeaseExpiry);
        assertThat(fixture.store().graphRowCount()).isPositive();
        assertThat(fixture.store().jobRowCount()).isEqualTo(2);

        // When
        var postExpiryDenialChecks = new AtomicInteger();
        fixture.objects().beforeNextDelete("retry-object", () -> {
            access.assertDenied();
            postExpiryDenialChecks.incrementAndGet();
        });
        fixture.clock().advance(BoardDeletionWorker.LEASE.minusSeconds(1));
        restartedWorker.runOnce();

        // Then
        assertThat(fixture.store().graphRowCount()).isZero();
        assertThat(fixture.store().jobRowCount()).isZero();
        assertThat(fixture.objects().deletes("retry-object")).isEqualTo(3);
        assertThat(postExpiryDenialChecks).hasValue(1);
        access.assertAbsent();
    }
}
