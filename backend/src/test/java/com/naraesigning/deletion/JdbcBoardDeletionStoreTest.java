package com.naraesigning.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.EncryptedValue;
import com.naraesigning.crypto.VersionedCryptoService;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionOperations;

class JdbcBoardDeletionStoreTest {
    @Test
    void givenOwnedBoardWithObjectWhenPhaseABeginsThenLockStateAndEncryptedJobCommitTogether() throws Exception {
        // Given
        var jdbc = mock(JdbcOperations.class);
        var crypto = new VersionedCryptoService(Map.of(1, new byte[32]), 1);
        var ownerId = UUID.randomUUID();
        var boardId = UUID.randomUUID();
        var assetId = UUID.randomUUID();
        var objectKey = "private/background-object".getBytes(StandardCharsets.UTF_8);
        var assetKey = crypto.encrypt(objectKey,
                CryptoContext.field("background-asset", assetId.toString(), "object-key"));
        var calls = new ArrayList<String>();
        var inserted = new ArrayList<Object[]>();
        stubLock(jdbc, calls, ownerId);
        stubAssets(jdbc, calls, assetId, assetKey);
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            var sql = invocation.getArgument(0, String.class);
            var invocationArguments = invocation.getArguments();
            var arguments = java.util.Arrays.copyOfRange(invocationArguments, 1, invocationArguments.length);
            if (sql.startsWith("update board set status")) calls.add("state");
            if (sql.contains("insert into board_deletion_job")) {
                calls.add("job");
                inserted.add(arguments);
            }
            return 1;
        });

        // When
        new JdbcBoardDeletionStore(jdbc, TransactionOperations.withoutTransaction(), crypto).begin(ownerId, boardId);

        // Then
        assertThat(calls).containsExactly("lock", "assets", "state", "job");
        assertThat(inserted).singleElement().satisfies(arguments -> {
            var jobId = (UUID) arguments[0];
            var encrypted = new EncryptedValue((byte[]) arguments[2], (byte[]) arguments[3], (int) arguments[4]);
            assertThat(arguments[1]).isEqualTo(boardId);
            assertThat(new String(encrypted.ciphertext(), StandardCharsets.UTF_8))
                    .doesNotContain(new String(objectKey, StandardCharsets.UTF_8));
            assertThat(crypto.decrypt(encrypted,
                    CryptoContext.field("board-deletion-job", jobId.toString(), "object-key")))
                    .isEqualTo(objectKey);
        });
    }

    @SuppressWarnings("unchecked")
    private static void stubLock(JdbcOperations jdbc, List<String> calls, UUID ownerId) throws Exception {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class))).thenAnswer(invocation -> {
            var sql = invocation.getArgument(0, String.class);
            if (!sql.startsWith("select owner_id")) return null;
            calls.add("lock");
            var result = mock(ResultSet.class);
            when(result.next()).thenReturn(true);
            when(result.getObject(1, UUID.class)).thenReturn(ownerId);
            when(result.getString(2)).thenReturn("OPEN");
            return ((ResultSetExtractor<Object>) invocation.getArgument(1)).extractData(result);
        });
    }

    @SuppressWarnings("unchecked")
    private static void stubAssets(JdbcOperations jdbc, List<String> calls, UUID assetId,
            EncryptedValue encrypted) throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            var sql = invocation.getArgument(0, String.class);
            if (!sql.contains("from background_asset")) return List.of();
            calls.add("assets");
            var result = mock(ResultSet.class);
            when(result.getObject("id", UUID.class)).thenReturn(assetId);
            when(result.getBytes("encrypted_object_key")).thenReturn(encrypted.ciphertext());
            when(result.getBytes("object_key_nonce")).thenReturn(encrypted.nonce());
            when(result.getInt("object_key_key_version")).thenReturn(encrypted.keyVersion());
            return List.of(((RowMapper<Object>) invocation.getArgument(1)).mapRow(result, 0));
        });
    }
}
