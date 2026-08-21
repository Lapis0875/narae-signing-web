package com.naraesigning.background;

import com.naraesigning.crypto.EncryptedValue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;

final class JdbcBackgroundAssetRepository implements BackgroundAssetRepository {
    private final JdbcOperations jdbc;

    JdbcBackgroundAssetRepository(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredBackgroundAsset> current(UUID boardId) {
        return Optional.ofNullable(jdbc.query("""
                select a.id, a.board_id, a.encrypted_object_key, a.object_key_nonce,
                       a.object_key_key_version, a.display_width, a.display_height, a.mime_type
                from board b join background_asset a on a.id = b.background_asset_id
                where b.id = ?
                """, resultSet -> resultSet.next() ? mapAsset(resultSet) : null, boardId));
    }

    @Override
    public void insertAndPoint(StoredBackgroundAsset asset) {
        var key = asset.encryptedObjectKey();
        jdbc.update("""
                insert into background_asset (
                    id, board_id, encrypted_object_key, object_key_nonce, object_key_key_version,
                    display_width, display_height, mime_type)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, asset.id(), asset.boardId(), key.ciphertext(), key.nonce(), key.keyVersion(),
                asset.displayWidth(), asset.displayHeight(), asset.mimeType());
        var changed = jdbc.update("""
                update board set background_asset_id = ?, canvas_width = ?, canvas_height = ?,
                    updated_at = current_timestamp
                where id = ? and status <> 'DELETING'
                """, asset.id(), asset.displayWidth(), asset.displayHeight(), asset.boardId());
        if (changed != 1) throw new BackgroundStoreException();
    }

    @Override
    public void insertCleanup(CleanupJob job) {
        var key = job.encryptedObjectKey();
        jdbc.update("""
                insert into board_deletion_job (
                    id, board_id, encrypted_object_key, object_key_nonce, object_key_key_version,
                    reason, status, attempt_count)
                values (?, ?, ?, ?, ?, ?, 'PENDING', 0)
                """, job.id(), job.boardId(), key.ciphertext(), key.nonce(), key.keyVersion(),
                job.reason().name());
    }

    @Override
    public List<CleanupJob> claimCleanupJobs() {
        return jdbc.query("""
                with candidates as (
                    select id from board_deletion_job
                    where reason in ('BACKGROUND_REPLACED', 'ORPHAN_CLEANUP') and (
                        (status = 'PENDING' and (next_attempt_at is null or next_attempt_at <= current_timestamp))
                        or (status = 'PROCESSING' and updated_at < current_timestamp - interval '5 minutes'))
                    order by created_at, id limit 20 for update skip locked
                )
                update board_deletion_job j set status = 'PROCESSING',
                    attempt_count = attempt_count + 1, updated_at = current_timestamp
                from candidates c where j.id = c.id
                returning j.id, j.board_id, j.encrypted_object_key, j.object_key_nonce,
                          j.object_key_key_version, j.reason, j.status, j.attempt_count
                """, (resultSet, rowNumber) -> mapJob(resultSet));
    }

    @Override
    public void retryCleanup(UUID id) {
        jdbc.update("""
                update board_deletion_job set status = 'PENDING',
                    next_attempt_at = current_timestamp + interval '1 minute', updated_at = current_timestamp
                where id = ? and status = 'PROCESSING'
                """, id);
    }

    @Override
    public void completeCleanup(UUID id) {
        jdbc.update("""
                update board_deletion_job set status = 'COMPLETED', completed_at = current_timestamp,
                    next_attempt_at = null, last_error = null, updated_at = current_timestamp
                where id = ? and status = 'PROCESSING'
                """, id);
    }

    private static StoredBackgroundAsset mapAsset(ResultSet resultSet) throws SQLException {
        return new StoredBackgroundAsset(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("board_id", UUID.class),
                encryptedKey(resultSet),
                resultSet.getInt("display_width"),
                resultSet.getInt("display_height"),
                resultSet.getString("mime_type"));
    }

    private static CleanupJob mapJob(ResultSet resultSet) throws SQLException {
        return new CleanupJob(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("board_id", UUID.class),
                encryptedKey(resultSet),
                CleanupReason.valueOf(resultSet.getString("reason")),
                CleanupStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count"));
    }

    private static EncryptedValue encryptedKey(ResultSet resultSet) throws SQLException {
        return new EncryptedValue(
                resultSet.getBytes("encrypted_object_key"),
                resultSet.getBytes("object_key_nonce"),
                resultSet.getInt("object_key_key_version"));
    }
}
