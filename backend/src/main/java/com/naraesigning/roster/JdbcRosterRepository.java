package com.naraesigning.roster;

import com.naraesigning.crypto.EncryptedValue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.ResultSetExtractor;

final class JdbcRosterRepository implements RosterRepository {
    private static final String COLUMNS = """
            r.id, r.board_id, r.encrypted_identity, r.identity_nonce, r.identity_key_version,
            r.identity_hmac, r.submitted, s.id slot_id, s.placement_status, s.x, s.y,
            s.width, s.height, s.background_color, s.slot_revision
            """;
    private final JdbcOperations jdbc;

    JdbcRosterRepository(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StoredRosterEntry> list(UUID boardId) {
        return jdbc.query("""
                select %s from roster_entry r join signature_slot s on s.roster_entry_id = r.id
                where r.board_id = ? order by r.created_at, r.id
                """.formatted(COLUMNS), (resultSet, rowNumber) -> map(resultSet), boardId);
    }

    @Override
    public StoredRosterEntry create(UUID boardId, NewRosterEntry entry) {
        requireEditableBoard(boardId);
        int count = jdbc.queryForObject("select count(*) from roster_entry where board_id = ?", Integer.class, boardId);
        if (count >= RosterService.MAX_ROWS) throw new RosterInputException("ROW_LIMIT");
        insert(boardId, entry);
        return find(boardId, entry.id());
    }

    @Override
    public StoredRosterEntry update(UUID boardId, UUID entryId, NewRosterEntry entry) {
        requireMutableEntry(boardId, entryId);
        int changed = jdbc.update("""
                update roster_entry set encrypted_identity = ?, identity_nonce = ?,
                    identity_key_version = ?, identity_hmac = ?, updated_at = current_timestamp
                where board_id = ? and id = ?
                """, entry.identity().ciphertext(), entry.identity().nonce(),
                entry.identity().keyVersion(), entry.hmac(), boardId, entryId);
        if (changed != 1) throw new RosterUnavailableException();
        return find(boardId, entryId);
    }

    @Override
    public void delete(UUID boardId, UUID entryId) {
        requireMutableEntry(boardId, entryId);
        if (jdbc.update("delete from roster_entry where board_id = ? and id = ?", boardId, entryId) != 1) {
            throw new RosterUnavailableException();
        }
    }

    @Override
    public List<StoredRosterEntry> replace(UUID boardId, List<NewRosterEntry> entries) {
        requireDraftBoard(boardId);
        Map<String, StoredRosterEntry> existing = list(boardId).stream()
                .collect(Collectors.toMap(value -> key(value.hmac()), Function.identity()));
        var retained = entries.stream().map(NewRosterEntry::hmac).map(JdbcRosterRepository::key).toList();
        existing.forEach((hmac, value) -> {
            if (!retained.contains(hmac)) {
                jdbc.update("delete from roster_entry where board_id = ? and id = ?", boardId, value.id());
            }
        });
        for (var entry : entries) {
            if (!existing.containsKey(key(entry.hmac()))) insert(boardId, entry);
        }
        return list(boardId);
    }

    private void requireEditableBoard(UUID boardId) {
        var status = jdbc.query("select status from board where id = ? and status in ('DRAFT', 'OPEN') for update",
                resultSet -> resultSet.next() ? resultSet.getString(1) : null, boardId);
        if (status == null) throw new RosterUnavailableException();
    }

    private void requireDraftBoard(UUID boardId) {
        var found = jdbc.query("select 1 from board where id = ? and status = 'DRAFT' for update",
                (ResultSetExtractor<Boolean>) ResultSet::next, boardId);
        if (!found) throw new RosterUnavailableException();
    }

    private void requireMutableEntry(UUID boardId, UUID entryId) {
        var found = jdbc.query("""
                select 1 from roster_entry r join board b on b.id = r.board_id
                where r.board_id = ? and r.id = ? and not r.submitted
                    and b.status in ('DRAFT', 'OPEN') for update of r, b
                """, (ResultSetExtractor<Boolean>) ResultSet::next, boardId, entryId);
        if (!found) throw new RosterUnavailableException();
    }

    private void insert(UUID boardId, NewRosterEntry entry) {
        var encrypted = entry.identity();
        jdbc.update("""
                insert into roster_entry (id, board_id, encrypted_identity, identity_nonce,
                    identity_key_version, identity_hmac) values (?, ?, ?, ?, ?, ?)
                """, entry.id(), boardId, encrypted.ciphertext(), encrypted.nonce(),
                encrypted.keyVersion(), entry.hmac());
        jdbc.update("""
                insert into signature_slot (id, roster_entry_id, placement_status)
                values (?, ?, 'UNPLACED')
                """, entry.slotId(), entry.id());
    }

    private StoredRosterEntry find(UUID boardId, UUID entryId) {
        var value = jdbc.query("""
                select %s from roster_entry r join signature_slot s on s.roster_entry_id = r.id
                where r.board_id = ? and r.id = ?
                """.formatted(COLUMNS), resultSet -> resultSet.next() ? map(resultSet) : null,
                boardId, entryId);
        if (value == null) throw new RosterUnavailableException();
        return value;
    }

    private static StoredRosterEntry map(ResultSet resultSet) throws SQLException {
        return new StoredRosterEntry(
                resultSet.getObject("id", UUID.class), resultSet.getObject("board_id", UUID.class),
                new EncryptedValue(resultSet.getBytes("encrypted_identity"),
                        resultSet.getBytes("identity_nonce"), resultSet.getInt("identity_key_version")),
                resultSet.getBytes("identity_hmac"), resultSet.getBoolean("submitted"),
                new StoredSlot(resultSet.getObject("slot_id", UUID.class),
                        resultSet.getString("placement_status"), resultSet.getBigDecimal("x"),
                        resultSet.getBigDecimal("y"), resultSet.getBigDecimal("width"),
                        resultSet.getBigDecimal("height"), resultSet.getString("background_color"),
                        resultSet.getLong("slot_revision")));
    }

    private static String key(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
