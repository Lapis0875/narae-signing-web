package com.naraesigning.signer.identify;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;

final class JdbcPublicIdentifyRepository implements PublicIdentifyRepository {
    private final JdbcOperations jdbc;

    JdbcPublicIdentifyRepository(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LinkRecord> findLink(UUID boardId, int linkVersion) {
        return Optional.ofNullable(jdbc.query("""
                select id, title, status, share_link_version, share_token_lookup_hash
                from board
                where id = ? and share_link_version = ? and status <> 'DELETING'
                """, resultSet -> resultSet.next() ? new LinkRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getString("status"),
                        resultSet.getInt("share_link_version"),
                        resultSet.getBytes("share_token_lookup_hash")) : null,
                boardId, linkVersion));
    }

    @Override
    public Optional<SignerRecord> findSigner(UUID boardId, int linkVersion, byte[] identityHmac) {
        return Optional.ofNullable(jdbc.query("""
                select b.id board_id, b.status board_status, b.share_link_version,
                       b.canvas_width, b.canvas_height, r.submitted,
                       s.id slot_id, s.placement_status, s.slot_revision, s.width, s.height
                from board b
                join roster_entry r on r.board_id = b.id
                join signature_slot s on s.roster_entry_id = r.id
                where b.id = ? and b.share_link_version = ? and b.status <> 'DELETING'
                  and r.identity_hmac = ?
                """, resultSet -> resultSet.next() ? signer(resultSet) : null,
                boardId, linkVersion, identityHmac));
    }

    @Override
    public Optional<SignerRecord> findSigner(UUID boardId, UUID slotId) {
        return Optional.ofNullable(jdbc.query("""
                select b.id board_id, b.status board_status, b.share_link_version,
                       b.canvas_width, b.canvas_height, r.submitted,
                       s.id slot_id, s.placement_status, s.slot_revision, s.width, s.height
                from board b
                join roster_entry r on r.board_id = b.id
                join signature_slot s on s.roster_entry_id = r.id
                where b.id = ? and s.id = ?
                """, resultSet -> resultSet.next() ? signer(resultSet) : null,
                boardId, slotId));
    }

    private static SignerRecord signer(ResultSet resultSet) throws SQLException {
        return new SignerRecord(
                resultSet.getObject("board_id", UUID.class),
                resultSet.getObject("slot_id", UUID.class),
                resultSet.getString("board_status"),
                resultSet.getInt("share_link_version"),
                resultSet.getBoolean("submitted"),
                resultSet.getString("placement_status"),
                resultSet.getLong("slot_revision"),
                resultSet.getBigDecimal("width"),
                resultSet.getBigDecimal("height"),
                resultSet.getInt("canvas_width"),
                resultSet.getInt("canvas_height"));
    }
}
