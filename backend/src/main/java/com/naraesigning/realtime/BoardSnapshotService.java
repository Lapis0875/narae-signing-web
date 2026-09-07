package com.naraesigning.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.EncryptedValue;
import com.naraesigning.crypto.VersionedCryptoService;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty("spring.datasource.url")
final class BoardSnapshotService {
    private final JdbcOperations jdbc;
    private final VersionedCryptoService crypto;
    private final ObjectMapper json;
    private final LiveSignatureRegistry drafts;

    BoardSnapshotService(JdbcOperations jdbc, VersionedCryptoService crypto, ObjectMapper json) {
        this(jdbc, crypto, json, null);
    }

    @Autowired
    BoardSnapshotService(
            JdbcOperations jdbc,
            VersionedCryptoService crypto,
            ObjectMapper json,
            LiveSignatureRegistry drafts) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.json = json;
        this.drafts = drafts;
    }

    BoardSnapshot read(UUID boardId, UUID ownerId) {
        var board = jdbc.query("""
                select id, canvas_width, canvas_height, background_asset_id is not null background_present
                from board where id = ? and owner_id = ? and status <> 'DELETING'
                """, result -> result.next() ? new BoardRow(
                        result.getInt("canvas_width"), result.getInt("canvas_height"),
                        result.getBoolean("background_present")) : null, boardId, ownerId);
        if (board == null) throw new SnapshotUnavailableException();
        return snapshot(boardId, board);
    }

    BoardSnapshot readPublic(UUID boardId) {
        var board = jdbc.query("""
                select id, canvas_width, canvas_height, background_asset_id is not null background_present
                from board where id = ? and status in ('OPEN', 'CLOSED')
                """, result -> result.next() ? new BoardRow(
                        result.getInt("canvas_width"), result.getInt("canvas_height"),
                        result.getBoolean("background_present")) : null, boardId);
        if (board == null) throw new SnapshotUnavailableException();
        return snapshot(boardId, board);
    }

    boolean publiclyVisible(UUID boardId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from board where id = ? and status in ('OPEN', 'CLOSED'))
                """, Boolean.class, boardId));
    }

    private BoardSnapshot snapshot(UUID boardId, BoardRow board) {
        var slots = jdbc.query("""
                select s.id, s.x, s.y, s.width, s.height, s.background_color,
                       s.encrypted_strokes, s.strokes_nonce, s.strokes_key_version
                from signature_slot s join roster_entry r on r.id = s.roster_entry_id
                where r.board_id = ? and s.placement_status = 'PLACED'
                order by s.id
                """, (result, row) -> slot(boardId, result), boardId);
        return new BoardSnapshot(boardId, board.width(), board.height(), board.backgroundPresent(), slots);
    }

    private BoardSnapshot.Slot slot(UUID boardId, ResultSet result) throws SQLException {
        var id = result.getObject("id", UUID.class);
        var encrypted = result.getBytes("encrypted_strokes");
        var signature = encrypted == null ? null : decrypt(result, id, encrypted);
        var live = drafts == null
                ? new LiveSignatureRegistry.Snapshot(null, 0, 0)
                : drafts.snapshot(boardId, id);
        return new BoardSnapshot.Slot(id, result.getBigDecimal("x"), result.getBigDecimal("y"),
                result.getBigDecimal("width"), result.getBigDecimal("height"),
                result.getString("background_color"), signature,
                signature == null ? live.signature() : null, live.draftEpoch(), live.revision());
    }

    private com.fasterxml.jackson.databind.JsonNode decrypt(ResultSet result, UUID slotId, byte[] encrypted)
            throws SQLException {
        var plaintext = crypto.decrypt(new EncryptedValue(encrypted, result.getBytes("strokes_nonce"),
                result.getInt("strokes_key_version")),
                CryptoContext.field("signature-slot", slotId.toString(), "strokes"));
        try {
            return json.readTree(plaintext);
        } catch (IOException exception) {
            throw new SnapshotUnavailableException();
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private record BoardRow(int width, int height, boolean backgroundPresent) {}
}
