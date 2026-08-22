package com.naraesigning.deletion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class BoardDeletionWorkerTest {
    @Test
    void givenTwoPollersWhenLeaseIsCurrentThenSecondCannotClaimBeforeExpiry() {
        // Given
        var fixture = PersistedDeletionFixture.create("object-key");
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var now = fixture.clock().instant();

        // When
        var firstClaim = fixture.store().claim(first, now, BoardDeletionWorker.LEASE);
        var duplicate = fixture.store().claim(second, now.plusSeconds(1), BoardDeletionWorker.LEASE);
        var reclaimed = fixture.store().claim(second, now.plus(BoardDeletionWorker.LEASE),
                BoardDeletionWorker.LEASE);

        // Then
        assertThat(firstClaim).hasSize(1);
        assertThat(duplicate).isEmpty();
        assertThat(reclaimed).singleElement().satisfies(job -> assertThat(job.leaseToken()).isEqualTo(second));
    }

    @Test
    void givenCurrentLeaseWhenRenewedThenReclaimUsesExtendedExpiry() {
        // Given
        var fixture = PersistedDeletionFixture.create("object-key");
        var token = UUID.randomUUID();
        var now = fixture.clock().instant();
        var claimed = fixture.store().claim(token, now, BoardDeletionWorker.LEASE).getFirst();

        // When
        var renewed = fixture.store().renew(claimed.id(), token, now.plusSeconds(30), BoardDeletionWorker.LEASE);
        var earlyReclaim = fixture.store().claim(UUID.randomUUID(), now.plus(BoardDeletionWorker.LEASE),
                BoardDeletionWorker.LEASE);

        // Then
        assertThat(renewed).isTrue();
        assertThat(earlyReclaim).isEmpty();
    }

    @Test
    void givenMissingObjectWhenWorkerRunsThenDeleteIsIdempotentAndGraphRowsAreRemoved() {
        // Given
        var fixture = PersistedDeletionFixture.create("already-missing");

        // When
        fixture.worker().runOnce();

        // Then
        assertThat(fixture.objects().deletes("already-missing")).isEqualTo(1);
        assertThat(fixture.store().graphRowCount()).isZero();
        assertThat(fixture.store().jobRowCount()).isZero();
    }
}
