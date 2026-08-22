package com.naraesigning.deletion;

import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.EncryptedValue;
import com.naraesigning.crypto.VersionedCryptoService;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

final class JdbcBoardDeletionStore implements BoardDeletionStore {
    private static final Duration MAX_RETRY = Duration.ofHours(1);
    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final VersionedCryptoService crypto;

    JdbcBoardDeletionStore(JdbcOperations jdbc, TransactionOperations transactions, VersionedCryptoService crypto) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.crypto = crypto;
    }

    @Override
    public void begin(UUID ownerId, UUID boardId) {
        transactions.executeWithoutResult(transaction -> {
            var board = jdbc.query("select owner_id, status from board where id = ? for update", result ->
                    result.next() ? new BoardLock(result.getObject(1, UUID.class), result.getString(2)) : null, boardId);
            if (board == null || !board.ownerId().equals(ownerId)) throw new BoardDeletionException("BOARD_UNAVAILABLE");
            if ("DELETING".equals(board.status())) return;

            var assets = jdbc.query("""
                    select id, encrypted_object_key, object_key_nonce, object_key_key_version
                    from background_asset where board_id = ? order by id
                    """, (result, row) -> new AssetKey(result.getObject("id", UUID.class), encrypted(result)), boardId);
            if (jdbc.update("update board set status = 'DELETING', updated_at = current_timestamp where id = ?",
                    boardId) != 1) throw new BoardDeletionException("BOARD_STATE_CONFLICT");
            for (var asset : assets) insertJob(boardId, asset);
        });
    }

    @Override
    public List<DeletionJob> claim(UUID leaseToken, Instant now, Duration leaseDuration) {
        return transactions.execute(transaction -> jdbc.query("""
                with candidates as (
                    select id from board_deletion_job
                    where reason = 'BOARD_DELETE' and (
                        (status = 'PENDING' and (next_attempt_at is null or next_attempt_at <= ?))
                        or (status = 'PROCESSING' and lease_expires_at <= ?))
                    order by created_at, id limit 20 for update skip locked
                )
                update board_deletion_job j set status = 'PROCESSING', attempt_count = attempt_count + 1,
                    lease_token = ?, lease_expires_at = ?, updated_at = ?
                from candidates c where j.id = c.id
                returning j.id, j.board_id, j.encrypted_object_key, j.object_key_nonce,
                          j.object_key_key_version, j.attempt_count, j.lease_token, j.lease_expires_at
                """, (result, row) -> mapJob(result), now, now, leaseToken, now.plus(leaseDuration), now));
    }

    @Override
    public boolean renew(UUID jobId, UUID leaseToken, Instant now, Duration leaseDuration) {
        return jdbc.update("""
                update board_deletion_job set lease_expires_at = ?, updated_at = ?
                where id = ? and status = 'PROCESSING' and lease_token = ? and lease_expires_at > ?
                """, now.plus(leaseDuration), now, jobId, leaseToken, now) == 1;
    }

    @Override
    public void complete(UUID jobId, UUID leaseToken) {
        jdbc.update("""
                update board_deletion_job set status = 'COMPLETED', completed_at = current_timestamp,
                    next_attempt_at = null, last_error = null, lease_token = null, lease_expires_at = null,
                    updated_at = current_timestamp where id = ? and status = 'PROCESSING' and lease_token = ?
                """, jobId, leaseToken);
    }

    @Override
    public void retry(UUID jobId, UUID leaseToken, Instant now, int attemptCount) {
        var exponent = Math.min(Math.max(attemptCount - 1, 0), 6);
        var delay = Duration.ofMinutes(1L << exponent);
        if (delay.compareTo(MAX_RETRY) > 0) delay = MAX_RETRY;
        jdbc.update("""
                update board_deletion_job set status = 'PENDING', next_attempt_at = ?,
                    last_error = 'OBJECT_DELETE_FAILED', lease_token = null, lease_expires_at = null,
                    updated_at = ? where id = ? and status = 'PROCESSING' and lease_token = ?
                """, now.plus(delay), now, jobId, leaseToken);
    }

    @Override
    public void finalizeReadyBoards() {
        transactions.executeWithoutResult(transaction -> {
            var ready = jdbc.query("""
                    select b.id from board b where b.status = 'DELETING' and not exists (
                        select 1 from board_deletion_job j where j.board_id = b.id and j.status <> 'COMPLETED')
                    order by b.id for update skip locked
                    """, (result, row) -> result.getObject(1, UUID.class));
            for (var boardId : ready) {
                jdbc.update("update board set background_asset_id = null where id = ?", boardId);
                jdbc.update("delete from board_deletion_job where board_id = ?", boardId);
                jdbc.update("delete from board where id = ? and status = 'DELETING'", boardId);
            }
        });
    }

    private void insertJob(UUID boardId, AssetKey asset) {
        var jobId = UUID.randomUUID();
        var plaintext = crypto.decrypt(asset.key(),
                CryptoContext.field("background-asset", asset.id().toString(), "object-key"));
        try {
            var encrypted = crypto.encrypt(plaintext,
                    CryptoContext.field("board-deletion-job", jobId.toString(), "object-key"));
            jdbc.update("""
                    insert into board_deletion_job (id, board_id, encrypted_object_key, object_key_nonce,
                        object_key_key_version, reason, status, attempt_count)
                    values (?, ?, ?, ?, ?, 'BOARD_DELETE', 'PENDING', 0)
                    """, jobId, boardId, encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion());
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static DeletionJob mapJob(ResultSet result) throws SQLException {
        return new DeletionJob(result.getObject("id", UUID.class), result.getObject("board_id", UUID.class),
                encrypted(result), result.getInt("attempt_count"), result.getObject("lease_token", UUID.class),
                result.getObject("lease_expires_at", Instant.class));
    }

    private static EncryptedValue encrypted(ResultSet result) throws SQLException {
        return new EncryptedValue(result.getBytes("encrypted_object_key"), result.getBytes("object_key_nonce"),
                result.getInt("object_key_key_version"));
    }

    private record BoardLock(UUID ownerId, String status) {}
    private record AssetKey(UUID id, EncryptedValue key) {}
}
