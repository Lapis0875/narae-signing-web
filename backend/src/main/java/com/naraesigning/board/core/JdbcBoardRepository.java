package com.naraesigning.board.core;

import com.naraesigning.crypto.EncryptedValue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;

final class JdbcBoardRepository implements BoardRepository {
    private static final String COLUMNS = """
            id, owner_id, title, status, canvas_width, canvas_height,
            share_link_version, share_token_lookup_hash, share_token_ciphertext,
            share_token_nonce, share_token_key_version, created_at, updated_at
            """;
    private final JdbcOperations jdbc;

    JdbcBoardRepository(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StoredBoard create(NewBoard board) {
        var share = board.share();
        var encrypted = share.encryptedToken();
        return required(jdbc.query("""
                insert into board (
                    id, owner_id, title, status, canvas_width, canvas_height,
                    share_link_version, share_token_lookup_hash, share_token_ciphertext,
                    share_token_nonce, share_token_key_version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning %s
                """.formatted(COLUMNS), resultSet -> resultSet.next() ? map(resultSet) : null,
                board.id(), board.owner().id(), board.title().value(), board.status().name(),
                board.canvasWidth(), board.canvasHeight(), share.version(), share.lookupHash(),
                encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion()));
    }

    @Override
    public List<StoredBoard> list(BoardOwner owner) {
        return jdbc.query("""
                select %s from board
                where owner_id = ? and status <> 'DELETING'
                order by created_at desc, id
                """.formatted(COLUMNS), (resultSet, rowNumber) -> map(resultSet), owner.id());
    }

    @Override
    public Optional<StoredBoard> find(BoardOwner owner, UUID boardId) {
        return optional(jdbc.query("""
                select %s from board
                where owner_id = ? and id = ? and status <> 'DELETING'
                """.formatted(COLUMNS), resultSet -> resultSet.next() ? map(resultSet) : null,
                owner.id(), boardId));
    }

    @Override
    public Optional<StoredBoard> rename(BoardOwner owner, UUID boardId, BoardTitle title) {
        return optional(jdbc.query("""
                update board set title = ?, updated_at = current_timestamp
                where owner_id = ? and id = ? and status <> 'DELETING'
                returning %s
                """.formatted(COLUMNS), resultSet -> resultSet.next() ? map(resultSet) : null,
                title.value(), owner.id(), boardId));
    }

    @Override
    public Optional<StoredBoard> lockShare(BoardOwner owner, UUID boardId) {
        return optional(jdbc.query("""
                select %s from board
                where owner_id = ? and id = ? and status <> 'DELETING'
                for update
                """.formatted(COLUMNS), resultSet -> resultSet.next() ? map(resultSet) : null,
                owner.id(), boardId));
    }

    @Override
    public Optional<StoredBoard> replaceShare(
            BoardOwner owner, UUID boardId, int expectedVersion, StoredShare share) {
        var encrypted = share.encryptedToken();
        return optional(jdbc.query("""
                update board set
                    share_link_version = ?, share_token_lookup_hash = ?,
                    share_token_ciphertext = ?, share_token_nonce = ?,
                    share_token_key_version = ?, updated_at = current_timestamp
                where owner_id = ? and id = ? and status <> 'DELETING'
                    and share_link_version = ?
                returning %s
                """.formatted(COLUMNS), resultSet -> resultSet.next() ? map(resultSet) : null,
                share.version(), share.lookupHash(), encrypted.ciphertext(), encrypted.nonce(),
                encrypted.keyVersion(), owner.id(), boardId, expectedVersion));
    }

    @Override
    public Optional<StoredPublicBoardLink> findPublicByLookupHash(byte[] lookupHash) {
        return optional(jdbc.query("""
                select id, title, status, share_link_version from board
                where share_token_lookup_hash = ? and status <> 'DELETING'
                """, resultSet -> resultSet.next()
                        ? new StoredPublicBoardLink(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("title"),
                                BoardStatus.valueOf(resultSet.getString("status")),
                                resultSet.getInt("share_link_version"))
                        : null,
                lookupHash));
    }

    private static StoredBoard map(ResultSet resultSet) throws SQLException {
        return new StoredBoard(
                resultSet.getObject("id", UUID.class),
                BoardOwner.synthetic(resultSet.getObject("owner_id", UUID.class)),
                new BoardTitle(resultSet.getString("title")),
                BoardStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("canvas_width"),
                resultSet.getInt("canvas_height"),
                new StoredShare(
                        resultSet.getInt("share_link_version"),
                        resultSet.getBytes("share_token_lookup_hash"),
                        new EncryptedValue(
                                resultSet.getBytes("share_token_ciphertext"),
                                resultSet.getBytes("share_token_nonce"),
                                resultSet.getInt("share_token_key_version"))),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static <T> Optional<T> optional(T value) {
        return Optional.ofNullable(value);
    }

    private static <T> T required(T value) {
        return Optional.ofNullable(value).orElseThrow(BoardUnavailableException::new);
    }
}
