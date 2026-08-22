package com.naraesigning.signature;

import com.naraesigning.slot.CanonicalAspect;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

final class JdbcSignatureSubmissionRepository implements SignatureSubmissionRepository {
    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;

    JdbcSignatureSubmissionRepository(JdbcOperations jdbc, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public <T> T withBoardThenSlotLocked(UUID boardId, UUID slotId, LockedAction<T> action) {
        return transactions.execute(status -> {
            var board = lockBoard(boardId);
            var state = lockSlot(board, slotId);
            return action.apply(state, (encrypted, submittedAt) -> {
                requireSingleUpdate(jdbc.update("""
                        update signature_slot set encrypted_strokes = ?, strokes_nonce = ?,
                            strokes_key_version = ?, submitted_at = ?
                        where id = ? and encrypted_strokes is null and strokes_nonce is null
                            and strokes_key_version is null and submitted_at is null
                        """, encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(),
                        Timestamp.from(submittedAt), slotId));
                requireSingleUpdate(jdbc.update("""
                        update roster_entry set submitted = true, updated_at = current_timestamp
                        where id = ? and submitted = false
                        """, state.rosterEntryId()));
            });
        });
    }

    private BoardLock lockBoard(UUID boardId) {
        var board = jdbc.query("""
                select id, status, share_link_version, canvas_width, canvas_height
                from board where id = ? for update
                """, resultSet -> resultSet.next() ? new BoardLock(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getInt("share_link_version"),
                        resultSet.getInt("canvas_width"),
                        resultSet.getInt("canvas_height")) : null, boardId);
        if (board == null) throw SignatureSubmitException.stale();
        return board;
    }

    private LockedState lockSlot(BoardLock board, UUID slotId) {
        var state = jdbc.query("""
                select r.board_id, r.id roster_entry_id, r.submitted,
                       s.id slot_id, s.placement_status, s.slot_revision, s.width, s.height,
                       s.encrypted_strokes, s.strokes_nonce, s.strokes_key_version, s.submitted_at
                from signature_slot s join roster_entry r on r.id = s.roster_entry_id
                where r.board_id = ? and s.id = ? for update of s, r
                """, resultSet -> resultSet.next() ? map(board, resultSet) : null,
                board.id(), slotId);
        if (state == null) throw SignatureSubmitException.stale();
        return state;
    }

    private static LockedState map(BoardLock board, ResultSet resultSet) throws SQLException {
        var width = resultSet.getBigDecimal("width");
        var height = resultSet.getBigDecimal("height");
        var aspect = width == null || height == null ? 0 : CanonicalAspect.from(
                width.multiply(BigDecimal.valueOf(board.canvasWidth())),
                height.multiply(BigDecimal.valueOf(board.canvasHeight()))).value().doubleValue();
        return new LockedState(
                resultSet.getObject("board_id", UUID.class),
                resultSet.getObject("slot_id", UUID.class),
                resultSet.getObject("roster_entry_id", UUID.class),
                board.status(),
                board.linkVersion(),
                resultSet.getString("placement_status"),
                resultSet.getLong("slot_revision"),
                aspect,
                resultSet.getBoolean("submitted"),
                resultSet.getBytes("encrypted_strokes") != null,
                resultSet.getBytes("strokes_nonce") != null,
                resultSet.getObject("strokes_key_version") != null,
                resultSet.getTimestamp("submitted_at") == null
                        ? null : resultSet.getTimestamp("submitted_at").toInstant());
    }

    private static void requireSingleUpdate(int changed) {
        if (changed != 1) throw SignatureSubmitException.invalidState();
    }

    private record BoardLock(
            UUID id, String status, int linkVersion, int canvasWidth, int canvasHeight) {}
}
