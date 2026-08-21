package com.naraesigning.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcSlotRepositoryTest {
    private static final UUID BOARD_ID = UUID.fromString("10000000-0000-0000-0000-000000000000");
    private static final UUID LOWER_SLOT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID HIGHER_SLOT_ID = UUID.fromString("f0000000-0000-0000-0000-000000000002");

    @Test
    void productionRepositoryLocksBoardThenSlotsInAscendingUuidOrder() {
        // Given
        var fixture = fixture(List.of(slot(HIGHER_SLOT_ID), slot(LOWER_SLOT_ID)));

        // When
        fixture.repository().withLockedLayout(BOARD_ID, locked -> locked.slots());

        // Then
        assertThat(fixture.lockTrace()).satisfiesExactly(
                trace -> assertThat(trace).isEqualTo("board:" + BOARD_ID),
                trace -> assertThat(trace).contains("slot-list:").contains("order by s.id"),
                trace -> assertThat(trace).isEqualTo("slot:" + LOWER_SLOT_ID),
                trace -> assertThat(trace).isEqualTo("slot:" + HIGHER_SLOT_ID));
        System.out.println("QA jdbc_lock_order=board,slot-list,ascending-slots");
    }

    @Test
    void productionResetPersistsSignatureClearSubmittedClearAndSingleRevisionIncrement() {
        // Given
        var current = slot(LOWER_SLOT_ID).withSubmissionState(true, true);
        var fixture = fixture(List.of(current));

        // When
        var changed = new SlotService(fixture.repository()).resetSignature(BOARD_ID, LOWER_SLOT_ID);

        // Then
        assertThat(changed.revision()).isEqualTo(8);
        assertThat(fixture.committedWrites()).satisfiesExactly(
                write -> {
                    assertThat(write.sql()).contains("encrypted_strokes = null", "strokes_nonce = null",
                            "strokes_key_version = null", "submitted_at = null", "slot_revision = ?");
                    assertThat(write.parameters()).containsExactly(8L, LOWER_SLOT_ID);
                },
                write -> {
                    assertThat(write.sql()).contains("update roster_entry set submitted = false");
                    assertThat(write.parameters()).containsExactly(current.rosterEntryId());
                });
        System.out.println("QA jdbc_reset committed_writes=2 revision=8 signature=clear submitted=false");
    }

    @Test
    void productionDeletePersistsUnplacedTransparentGeometryClearAndRevisionIncrement() {
        // Given
        var current = slot(LOWER_SLOT_ID).withSubmissionState(true, true);
        var fixture = fixture(List.of(current));

        // When
        var changed = new SlotService(fixture.repository()).delete(BOARD_ID, LOWER_SLOT_ID);

        // Then
        assertThat(changed.revision()).isEqualTo(8);
        assertThat(changed.placed()).isFalse();
        assertThat(fixture.committedWrites()).first().satisfies(write -> {
            assertThat(write.sql()).contains("placement_status = 'UNPLACED'", "x = null", "y = null",
                    "width = null", "height = null", "encrypted_strokes = null", "slot_revision = ?");
            assertThat(write.parameters()).containsExactly("transparent", 8L, LOWER_SLOT_ID);
        });
        assertThat(fixture.committedWrites()).element(1).satisfies(write ->
                assertThat(write.sql()).contains("update roster_entry set submitted = false"));
        System.out.println("QA jdbc_delete placement=UNPLACED background=transparent geometry=clear revision=8");
    }

    @Test
    void productionTransactionDoesNotCommitFirstMutationWhenSecondWriteFails() {
        // Given
        var fixture = fixture(List.of(slot(LOWER_SLOT_ID).withSubmissionState(true, true)));
        fixture.failOnWrite(2);

        // When / Then
        assertThatThrownBy(() -> new SlotService(fixture.repository()).resetSignature(BOARD_ID, LOWER_SLOT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected JDBC write failure");
        assertThat(fixture.attemptedWrites()).isEqualTo(2);
        assertThat(fixture.committedWrites()).isEmpty();
        System.out.println("QA jdbc_rollback attempted_writes=2 committed_writes=0");
    }

    private static CapturingJdbcSlotFixture fixture(List<Slot> slots) {
        return new CapturingJdbcSlotFixture(BOARD_ID, new CanvasSize(1920, 1080), slots);
    }

    private static Slot slot(UUID slotId) {
        return new Slot(BOARD_ID, slotId, UUID.fromString("30000000-0000-0000-0000-000000000001"),
                SlotBounds.of(decimal("0.1"), decimal("0.2"), decimal("0.3"), decimal("0.4")),
                SlotBackground.WHITE, 7, false, false);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
