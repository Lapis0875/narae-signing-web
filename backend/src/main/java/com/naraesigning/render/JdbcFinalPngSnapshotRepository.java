package com.naraesigning.render;

import com.naraesigning.crypto.EncryptedValue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

final class JdbcFinalPngSnapshotRepository implements FinalPngSnapshotRepository {
    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;

    JdbcFinalPngSnapshotRepository(JdbcOperations jdbc, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public FinalPngSnapshot readClosed(UUID ownerId, UUID boardId) {
        return transactions.execute(status -> {
            var board = lockBoard(boardId);
            if (!board.ownerId().equals(ownerId)) throw FinalPngException.forbidden();
            if (!"CLOSED".equals(board.status())) throw FinalPngException.notClosed();
            return new FinalPngSnapshot(board.width(), board.height(), board.background(), lockSlots(boardId));
        });
    }

    private BoardRow lockBoard(UUID boardId) {
        var board = jdbc.query("""
                select b.owner_id, b.status, b.canvas_width, b.canvas_height,
                       a.id background_id, a.encrypted_object_key, a.object_key_nonce,
                       a.object_key_key_version
                from board b left join background_asset a on a.id = b.background_asset_id
                where b.id = ? for update of b
                """, result -> result.next() ? mapBoard(result) : null, boardId);
        if (board == null) throw FinalPngException.forbidden();
        return board;
    }

    private List<FinalPngEncryptedSlot> lockSlots(UUID boardId) {
        return jdbc.query("""
                select s.id, s.x, s.y, s.width, s.height, s.background_color,
                       s.encrypted_strokes, s.strokes_nonce, s.strokes_key_version
                from signature_slot s join roster_entry r on r.id = s.roster_entry_id
                where r.board_id = ? and s.placement_status = 'PLACED'
                order by s.id for update of s
                """, (result, rowNumber) -> mapSlot(result), boardId);
    }

    private static BoardRow mapBoard(ResultSet result) throws SQLException {
        var assetId = result.getObject("background_id", UUID.class);
        var background = assetId == null ? null : new FinalPngBackground(assetId,
                new EncryptedValue(result.getBytes("encrypted_object_key"),
                        result.getBytes("object_key_nonce"), result.getInt("object_key_key_version")));
        return new BoardRow(result.getObject("owner_id", UUID.class), result.getString("status"),
                result.getInt("canvas_width"), result.getInt("canvas_height"), background);
    }

    private static FinalPngEncryptedSlot mapSlot(ResultSet result) throws SQLException {
        var ciphertext = result.getBytes("encrypted_strokes");
        var strokes = ciphertext == null ? null : new EncryptedValue(ciphertext,
                result.getBytes("strokes_nonce"), result.getInt("strokes_key_version"));
        return new FinalPngEncryptedSlot(result.getObject("id", UUID.class),
                result.getBigDecimal("x"), result.getBigDecimal("y"),
                result.getBigDecimal("width"), result.getBigDecimal("height"),
                "white".equalsIgnoreCase(result.getString("background_color")), strokes);
    }

    private record BoardRow(
            UUID ownerId, String status, int width, int height, FinalPngBackground background) {}
}
