package com.naraesigning.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SlotServiceTest {
    private static final UUID BOARD_ID = UUID.fromString("10000000-0000-0000-0000-000000000000");

    @Test
    void placesTwentyFiveSlotsWithTouchingEdgesAndPreservesRevisionOnVisualChanges() {
        // Given
        var repository = repository(25);
        var service = new SlotService(repository);
        var slots = repository.slotIds();

        // When
        for (int index = 0; index < slots.size(); index++) {
            service.updateVisual(BOARD_ID, slots.get(index), bounds(index * 4, 0, 4, 100));
        }
        service.updateVisual(BOARD_ID, slots.get(0), bounds(0, 0, 4, 50));

        // Then
        assertThat(repository.slots()).hasSize(25).allMatch(Slot::placed);
        assertThat(repository.required(slots.get(0)).revision()).isEqualTo(1);
        assertThat(repository.trace()).startsWith("board:" + BOARD_ID);
        assertThat(repository.lockedSlotIds()).isSortedAccordingTo(Comparator.comparing(UUID::toString));
        System.out.println("QA slot_25_visual=success touching_edges=accepted revision=1");
    }

    @Test
    void rejectsOverlapBeforeAnyWriteAndRollsBackMutationFailure() {
        // Given
        var repository = repository(2);
        var service = new SlotService(repository);
        var slots = repository.slotIds();
        service.updateVisual(BOARD_ID, slots.get(0), bounds(0, 0, 50, 50));
        var beforeConflict = repository.snapshot();
        var writesBeforeConflict = repository.writeCount();

        // When / Then
        assertThatThrownBy(() -> service.updateVisual(
                BOARD_ID, slots.get(1), bounds(49, 0, 50, 50)))
                .isInstanceOf(SlotConflictException.class)
                .extracting("code").isEqualTo(SlotConflictException.Code.OVERLAP);
        assertThat(repository.snapshot()).isEqualTo(beforeConflict);
        assertThat(repository.writeCount()).isEqualTo(writesBeforeConflict);
        repository.failNextWriteAfterMutation();
        assertThatThrownBy(() -> service.delete(BOARD_ID, slots.get(0)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.snapshot()).isEqualTo(beforeConflict);
        System.out.println("QA slot_conflict=OVERLAP write_before_validation=0 rollback=unchanged");
    }

    @Test
    void revisionMatrixAndResetDeleteOutcomesAreExact() {
        // Given
        var repository = repository(1);
        var service = new SlotService(repository);
        var slotId = repository.slotIds().get(0);
        service.updateVisual(BOARD_ID, slotId, bounds(0, 0, 25, 25));
        repository.seedSubmitted(slotId);

        // When / Then
        service.resetSignature(BOARD_ID, slotId);
        assertThat(repository.required(slotId)).satisfies(slot -> {
            assertThat(slot.revision()).isEqualTo(2);
            assertThat(slot.submitted()).isFalse();
            assertThat(slot.signaturePresent()).isFalse();
            assertThat(slot.placed()).isTrue();
        });
        service.identityEdited(BOARD_ID, slotId);
        assertThat(repository.required(slotId).revision()).isEqualTo(3);
        service.delete(BOARD_ID, slotId);
        assertThat(repository.required(slotId)).satisfies(slot -> {
            assertThat(slot.revision()).isEqualTo(4);
            assertThat(slot.placed()).isFalse();
            assertThat(slot.bounds()).isNull();
        });
        service.updateVisual(BOARD_ID, slotId, bounds(25, 25, 25, 25));
        assertThat(repository.required(slotId).revision()).isEqualTo(5);
        System.out.println("QA slot_revision place=1 move=1 reset=2 identity=3 delete=4 reassign=5");
    }

    @Test
    void twoBarrierReleasedOverlappingWritesHaveOneWinner() throws Exception {
        // Given
        var repository = repository(2);
        repository.awaitConcurrentEntrants(2);
        var service = new SlotService(repository);
        var slots = repository.slotIds();
        var successes = new AtomicInteger();
        var conflicts = new AtomicInteger();

        // When
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (var slotId : slots) {
                executor.submit(() -> {
                    try {
                        service.updateVisual(BOARD_ID, slotId, bounds(0, 0, 50, 50));
                        successes.incrementAndGet();
                    } catch (SlotConflictException exception) {
                        if (exception.code() == SlotConflictException.Code.OVERLAP) conflicts.incrementAndGet();
                    }
                });
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        // Then
        assertThat(successes).hasValue(1);
        assertThat(conflicts).hasValue(1);
        assertThat(repository.slots().stream().filter(Slot::placed)).hasSize(1);
        System.out.println("QA concurrent_overlap successes=1 conflicts=1 losing_state=unchanged");
    }

    @Test
    void resetWinsAgainstBarrierHeldStaleSubmit() throws Exception {
        // Given
        var repository = repository(1);
        var service = new SlotService(repository);
        var slotId = repository.slotIds().get(0);
        service.updateVisual(BOARD_ID, slotId, bounds(0, 0, 50, 25));
        var submitReady = new CountDownLatch(1);
        var resetDone = new CountDownLatch(1);
        var submitFailure = new AtomicReference<Throwable>();

        // When
        try (var executor = Executors.newFixedThreadPool(2)) {
            executor.submit(() -> {
                submitReady.countDown();
                await(resetDone);
                try {
                    service.submit(BOARD_ID, slotId, 1, CanonicalAspect.of(decimal("2")),
                            new SlotSignature(new byte[] {1}, new byte[] {2}, 1, Instant.EPOCH));
                } catch (Throwable failure) {
                    submitFailure.set(failure);
                }
            });
            executor.submit(() -> {
                await(submitReady);
                service.resetSignature(BOARD_ID, slotId);
                resetDone.countDown();
            });
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        // Then
        assertThat(submitFailure.get()).isInstanceOf(SlotConflictException.class);
        assertThat(((SlotConflictException) submitFailure.get()).code())
                .isEqualTo(SlotConflictException.Code.STALE_SLOT);
        assertThat(repository.required(slotId).signaturePresent()).isFalse();
        assertThat(repository.required(slotId).revision()).isEqualTo(2);
        System.out.println("QA reset_vs_submit reset=winner submit=STALE_SLOT signature=empty");
    }

    @Test
    void resizePreservesRevisionButInvalidatesCanonicalAspect() {
        // Given
        var repository = repository(1);
        var service = new SlotService(repository);
        var slotId = repository.slotIds().get(0);
        service.updateVisual(BOARD_ID, slotId, bounds(0, 0, 50, 25));
        var revision = repository.required(slotId).revision();

        // When
        service.updateVisual(BOARD_ID, slotId, bounds(0, 0, 50, 50));

        // Then
        assertThat(repository.required(slotId).revision()).isEqualTo(revision);
        assertThatThrownBy(() -> service.submit(BOARD_ID, slotId, revision, CanonicalAspect.of(decimal("2")),
                new SlotSignature(new byte[] {1}, new byte[] {2}, 1, Instant.EPOCH)))
                .isInstanceOf(SlotConflictException.class)
                .extracting("code").isEqualTo(SlotConflictException.Code.STALE_ASPECT);
    }

    @Test
    void identityEditRejectsSubmittedSlotWithoutRevisionChange() {
        // Given
        var repository = repository(1);
        var service = new SlotService(repository);
        var slotId = repository.slotIds().get(0);
        repository.seedSubmitted(slotId);

        // When / Then
        assertThatThrownBy(() -> service.identityEdited(BOARD_ID, slotId))
                .isInstanceOf(SlotConflictException.class)
                .extracting("code").isEqualTo(SlotConflictException.Code.ALREADY_SUBMITTED);
        assertThat(repository.required(slotId).revision()).isZero();
    }

    @Test
    void canonicalAspectIncludesCanvasPixelDimensions() {
        // Given
        var repository = repository(1, 2000, 1000);
        var service = new SlotService(repository);
        var slotId = repository.slotIds().get(0);
        service.updateVisual(BOARD_ID, slotId, bounds(0, 0, 50, 50));

        // When / Then
        assertThatThrownBy(() -> service.submit(BOARD_ID, slotId, 1, CanonicalAspect.of(decimal("1")),
                new SlotSignature(new byte[] {1}, new byte[] {2}, 1, Instant.EPOCH)))
                .isInstanceOf(SlotConflictException.class)
                .extracting("code").isEqualTo(SlotConflictException.Code.STALE_ASPECT);
    }

    private static InMemorySlotRepository repository(int count) {
        return repository(count, 1000, 1000);
    }

    private static InMemorySlotRepository repository(int count, int canvasWidth, int canvasHeight) {
        var slots = new ArrayList<Slot>();
        for (int index = 0; index < count; index++) {
            var suffix = String.format("%012d", index + 1);
            slots.add(Slot.unplaced(BOARD_ID, UUID.fromString("20000000-0000-0000-0000-" + suffix),
                    UUID.fromString("30000000-0000-0000-0000-" + suffix)));
        }
        return new InMemorySlotRepository(slots, canvasWidth, canvasHeight);
    }

    private static SlotBounds bounds(int x, int y, int width, int height) {
        return SlotBounds.of(decimal(x + "").movePointLeft(2), decimal(y + "").movePointLeft(2),
                decimal(width + "").movePointLeft(2), decimal(height + "").movePointLeft(2));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("barrier interrupted", exception);
        }
    }

}
