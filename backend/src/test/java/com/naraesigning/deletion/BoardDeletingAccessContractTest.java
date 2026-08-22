package com.naraesigning.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.board.core.BoardOwner;
import com.naraesigning.board.core.BoardService;
import com.naraesigning.board.core.BoardUnavailableException;
import com.naraesigning.crypto.VersionedCryptoService;
import com.naraesigning.session.AdminSessionContract;
import com.naraesigning.session.SignerSessionContract;
import java.lang.reflect.InvocationTargetException;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.support.TransactionOperations;

class BoardDeletingAccessContractTest {
    @Test
    void givenDeletingBoardWhenReadThroughCurrentContractsThenEverySurfaceDeniesAccess() throws Exception {
        // Given
        var ownerId = UUID.randomUUID();
        var boardId = UUID.randomUUID();
        var jdbc = deletingBoardJdbc(ownerId);
        var crypto = new VersionedCryptoService(Map.of(1, new byte[32]), 1);
        var owner = owner(ownerId);
        var boards = boardService(jdbc, crypto);

        // When / Then
        assertThat(boards.list(owner)).isEmpty();
        assertThatThrownBy(() -> boards.detail(owner, boardId)).isInstanceOf(BoardUnavailableException.class);
        assertThatThrownBy(() -> boards.currentShare(owner, boardId)).isInstanceOf(BoardUnavailableException.class);
        assertThat(publicLink(jdbc)).isEmpty();
        assertThat(signerState(boardId)).isEqualTo(SignerSessionContract.State.UNAVAILABLE);
        assertThatThrownBy(() -> snapshot(jdbc, crypto, boardId, ownerId))
                .isInstanceOf(InvocationTargetException.class)
                .cause().hasToString("com.naraesigning.realtime.SnapshotUnavailableException");
        assertThatThrownBy(() -> finalPng(jdbc, boardId, ownerId))
                .isInstanceOf(InvocationTargetException.class)
                .cause().hasMessage("FINAL_PNG_NOT_CLOSED");
    }

    private static BoardOwner owner(UUID ownerId) {
        var session = new MockHttpSession();
        AdminSessionContract.issue(session, ownerId, Instant.parse("2026-08-22T00:00:00Z"));
        return BoardOwner.fromSession(session);
    }

    private static BoardService boardService(JdbcOperations jdbc, VersionedCryptoService crypto) throws Exception {
        var repositoryType = Class.forName("com.naraesigning.board.core.BoardRepository");
        var repositoryClass = Class.forName("com.naraesigning.board.core.JdbcBoardRepository");
        var repositoryConstructor = repositoryClass.getDeclaredConstructor(JdbcOperations.class);
        repositoryConstructor.setAccessible(true);
        var repository = repositoryConstructor.newInstance(jdbc);
        var serviceConstructor = BoardService.class.getDeclaredConstructor(
                repositoryType, VersionedCryptoService.class, TransactionOperations.class);
        serviceConstructor.setAccessible(true);
        return serviceConstructor.newInstance(repository, crypto, TransactionOperations.withoutTransaction());
    }

    private static Optional<?> publicLink(JdbcOperations jdbc) throws Exception {
        var repositoryClass = Class.forName("com.naraesigning.board.core.JdbcBoardRepository");
        var constructor = repositoryClass.getDeclaredConstructor(JdbcOperations.class);
        constructor.setAccessible(true);
        var repository = constructor.newInstance(jdbc);
        var method = repositoryClass.getDeclaredMethod("findPublicByLookupHash", byte[].class);
        method.setAccessible(true);
        return (Optional<?>) method.invoke(repository, (Object) new byte[32]);
    }

    private static SignerSessionContract.State signerState(UUID boardId) {
        var signer = new SignerSessionContract.Value(
                boardId, UUID.randomUUID(), 1, 0, 1.0, Instant.parse("2026-08-22T00:00:00Z"));
        return SignerSessionContract.validate(signer,
                new SignerSessionContract.CurrentState(1, 0, 1.0, "DELETING"));
    }

    private static Object snapshot(JdbcOperations jdbc, VersionedCryptoService crypto,
            UUID boardId, UUID ownerId) throws Exception {
        var type = Class.forName("com.naraesigning.realtime.BoardSnapshotService");
        var constructor = type.getDeclaredConstructor(
                JdbcOperations.class, VersionedCryptoService.class, ObjectMapper.class);
        constructor.setAccessible(true);
        var service = constructor.newInstance(jdbc, crypto, new ObjectMapper());
        var method = type.getDeclaredMethod("read", UUID.class, UUID.class);
        method.setAccessible(true);
        return method.invoke(service, boardId, ownerId);
    }

    private static Object finalPng(JdbcOperations jdbc, UUID boardId, UUID ownerId) throws Exception {
        var type = Class.forName("com.naraesigning.render.JdbcFinalPngSnapshotRepository");
        var constructor = type.getDeclaredConstructor(JdbcOperations.class, TransactionOperations.class);
        constructor.setAccessible(true);
        var repository = constructor.newInstance(jdbc, TransactionOperations.withoutTransaction());
        var method = type.getDeclaredMethod("readClosed", UUID.class, UUID.class);
        method.setAccessible(true);
        return method.invoke(repository, ownerId, boardId);
    }

    @SuppressWarnings("unchecked")
    private static JdbcOperations deletingBoardJdbc(UUID ownerId) throws Exception {
        var jdbc = mock(JdbcOperations.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            requireDeletingExclusion(invocation.getArgument(0, String.class));
            return List.of();
        });
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class))).thenAnswer(invocation -> {
            var sql = invocation.getArgument(0, String.class);
            var result = mock(ResultSet.class);
            if (sql.contains("left join background_asset")) {
                when(result.next()).thenReturn(true);
                when(result.getObject("owner_id", UUID.class)).thenReturn(ownerId);
                when(result.getString("status")).thenReturn("DELETING");
                when(result.getInt("canvas_width")).thenReturn(1920);
                when(result.getInt("canvas_height")).thenReturn(1080);
            } else {
                requireDeletingExclusion(sql);
                when(result.next()).thenReturn(false);
            }
            return ((ResultSetExtractor<Object>) invocation.getArgument(1)).extractData(result);
        });
        return jdbc;
    }

    private static void requireDeletingExclusion(String sql) {
        if (!sql.toUpperCase(java.util.Locale.ROOT).contains("STATUS <> 'DELETING'")) {
            throw new AssertionError("DELETING row leaked through current read contract");
        }
    }
}
