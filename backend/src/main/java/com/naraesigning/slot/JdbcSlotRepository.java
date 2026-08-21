package com.naraesigning.slot;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

final class JdbcSlotRepository implements SlotRepository {
    private static final String SLOT_COLUMNS = """
            r.board_id, s.id, s.roster_entry_id, s.placement_status, s.x, s.y,
            s.width, s.height, s.background_color, s.slot_revision, r.submitted,
            (s.encrypted_strokes is not null) signature_present
            """;
    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;

    JdbcSlotRepository(JdbcOperations jdbc, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public <T> T withLockedLayout(UUID boardId, Function<LockedLayout, T> action) {
        return transactions.execute(status -> {
            var canvas = lockBoard(boardId);
            var slotIds = jdbc.query("""
                    select s.id from signature_slot s join roster_entry r on r.id = s.roster_entry_id
                    where r.board_id = ? order by s.id
                    """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class), boardId);
            var slots = slotIds.stream().map(slotId -> lockSlot(boardId, slotId)).toList();
            return action.apply(new JdbcLockedLayout(canvas, slots));
        });
    }

    private CanvasSize lockBoard(UUID boardId) {
        var canvas = jdbc.query("""
                select canvas_width, canvas_height from board
                where id = ? and status <> 'DELETING' for update
                """, resultSet -> resultSet.next()
                        ? new CanvasSize(resultSet.getInt(1), resultSet.getInt(2)) : null, boardId);
        if (canvas == null) throw new SlotConflictException(SlotConflictException.Code.BOARD_UNAVAILABLE);
        return canvas;
    }

    private Slot lockSlot(UUID boardId, UUID slotId) {
        var slot = jdbc.query("""
                select %s from signature_slot s join roster_entry r on r.id = s.roster_entry_id
                where r.board_id = ? and s.id = ? for update of s, r
                """.formatted(SLOT_COLUMNS), resultSet -> resultSet.next() ? map(resultSet) : null,
                boardId, slotId);
        if (slot == null) throw new SlotConflictException(SlotConflictException.Code.SLOT_NOT_FOUND);
        return slot;
    }

    private static Slot map(ResultSet resultSet) throws SQLException {
        var placed = "PLACED".equals(resultSet.getString("placement_status"));
        var bounds = placed ? SlotBounds.of(resultSet.getBigDecimal("x"), resultSet.getBigDecimal("y"),
                resultSet.getBigDecimal("width"), resultSet.getBigDecimal("height")) : null;
        return new Slot(resultSet.getObject("board_id", UUID.class), resultSet.getObject("id", UUID.class),
                resultSet.getObject("roster_entry_id", UUID.class), bounds,
                SlotBackground.fromDatabase(resultSet.getString("background_color")),
                resultSet.getLong("slot_revision"), resultSet.getBoolean("submitted"),
                resultSet.getBoolean("signature_present"));
    }

    private final class JdbcLockedLayout implements LockedLayout {
        private final CanvasSize canvas;
        private final List<Slot> slots;

        private JdbcLockedLayout(CanvasSize canvas, List<Slot> slots) {
            this.canvas = canvas;
            this.slots = slots;
        }

        @Override
        public CanvasSize canvas() {
            return canvas;
        }

        @Override
        public List<Slot> slots() {
            return slots;
        }

        @Override
        public void save(Slot slot, SlotWrite write) {
            switch (write) {
                case VISUAL -> saveVisual(slot);
                case RESET -> clearSignature(slot, false);
                case DELETE -> clearSignature(slot, true);
                case IDENTITY_EDIT -> updateRevision(slot);
            }
        }

        @Override
        public void saveSubmission(Slot slot, SlotSignature signature) {
            requireSingleUpdate(jdbc.update("""
                    update signature_slot set encrypted_strokes = ?, strokes_nonce = ?,
                        strokes_key_version = ?, submitted_at = ? where id = ?
                    """, signature.ciphertext(), signature.nonce(), signature.keyVersion(),
                    signature.submittedAt(), slot.id()));
            requireSingleUpdate(jdbc.update("""
                    update roster_entry set submitted = true, updated_at = current_timestamp where id = ?
                    """, slot.rosterEntryId()));
        }

        private void saveVisual(Slot slot) {
            requireSingleUpdate(jdbc.update("""
                    update signature_slot set placement_status = 'PLACED', x = ?, y = ?,
                        width = ?, height = ?, background_color = ?, slot_revision = ? where id = ?
                    """, slot.bounds().x(), slot.bounds().y(), slot.bounds().width(), slot.bounds().height(),
                    slot.background().databaseValue(), slot.revision(), slot.id()));
        }

        private void clearSignature(Slot slot, boolean deletePlacement) {
            if (deletePlacement) {
                requireSingleUpdate(jdbc.update("""
                        update signature_slot set placement_status = 'UNPLACED', x = null, y = null,
                            width = null, height = null, background_color = ?, encrypted_strokes = null,
                            strokes_nonce = null, strokes_key_version = null, submitted_at = null,
                            slot_revision = ? where id = ?
                        """, slot.background().databaseValue(), slot.revision(), slot.id()));
            } else {
                requireSingleUpdate(jdbc.update("""
                        update signature_slot set encrypted_strokes = null, strokes_nonce = null,
                            strokes_key_version = null, submitted_at = null, slot_revision = ? where id = ?
                        """, slot.revision(), slot.id()));
            }
            requireSingleUpdate(jdbc.update("""
                    update roster_entry set submitted = false, updated_at = current_timestamp where id = ?
                    """, slot.rosterEntryId()));
        }

        private void updateRevision(Slot slot) {
            requireSingleUpdate(jdbc.update("""
                    update signature_slot set slot_revision = ? where id = ?
                    """, slot.revision(), slot.id()));
        }

        private void requireSingleUpdate(int changed) {
            if (changed != 1) throw new SlotConflictException(SlotConflictException.Code.SLOT_NOT_FOUND);
        }
    }
}
