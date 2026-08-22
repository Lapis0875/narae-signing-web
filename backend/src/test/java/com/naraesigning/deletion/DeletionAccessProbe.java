package com.naraesigning.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
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
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.support.TransactionOperations;

final class DeletionAccessProbe {
    private final PersistedDeletionFixture.PersistedStore state;
    private final UUID ownerId = UUID.randomUUID();
    private final UUID boardId;
    private final JdbcOperations jdbc;
    private final VersionedCryptoService crypto;
    private final BoardOwner owner;
    private final BoardService boards;
    private final Object snapshotService;
    private final Method snapshotRead;
    private final Object finalPngRepository;
    private final Method finalPngRead;

    private DeletionAccessProbe(PersistedDeletionFixture.PersistedStore state) throws ReflectiveOperationException {
        this.state = state;
        this.boardId = state.boardId();
        this.jdbc = statefulJdbc();
        this.crypto = new VersionedCryptoService(Map.of(1, new byte[32]), 1);
        this.owner = owner(ownerId);
        this.boards = boardService(jdbc, crypto);
        var snapshotType = Class.forName("com.naraesigning.realtime.BoardSnapshotService");
        var snapshotConstructor = snapshotType.getDeclaredConstructor(
                JdbcOperations.class, VersionedCryptoService.class, ObjectMapper.class);
        snapshotConstructor.setAccessible(true);
        this.snapshotService = snapshotConstructor.newInstance(jdbc, crypto, new ObjectMapper());
        this.snapshotRead = snapshotType.getDeclaredMethod("read", UUID.class, UUID.class);
        snapshotRead.setAccessible(true);
        var finalPngType = Class.forName("com.naraesigning.render.JdbcFinalPngSnapshotRepository");
        var finalPngConstructor = finalPngType.getDeclaredConstructor(
                JdbcOperations.class, TransactionOperations.class);
        finalPngConstructor.setAccessible(true);
        this.finalPngRepository = finalPngConstructor.newInstance(jdbc, TransactionOperations.withoutTransaction());
        this.finalPngRead = finalPngType.getDeclaredMethod("readClosed", UUID.class, UUID.class);
        finalPngRead.setAccessible(true);
    }

    static DeletionAccessProbe forState(PersistedDeletionFixture.PersistedStore state) {
        try {
            return new DeletionAccessProbe(state);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("current access contract unavailable", exception);
        }
    }

    void assertDenied() {
        assertCommonDenial();
        assertFinalPngCode("FINAL_PNG_NOT_CLOSED");
    }

    void assertAbsent() {
        assertCommonDenial();
        assertFinalPngCode("FINAL_PNG_FORBIDDEN");
    }

    private void assertCommonDenial() {
        assertThat(boards.list(owner)).isEmpty();
        assertThatThrownBy(() -> boards.detail(owner, boardId)).isInstanceOf(BoardUnavailableException.class);
        assertThatThrownBy(() -> boards.currentShare(owner, boardId)).isInstanceOf(BoardUnavailableException.class);
        assertThat(publicLink()).isEmpty();
        assertThat(signerState()).isEqualTo(SignerSessionContract.State.UNAVAILABLE);
        try {
            snapshotRead.invoke(snapshotService, boardId, ownerId);
            fail("SSE snapshot must be unavailable");
        } catch (InvocationTargetException exception) {
            assertThat(exception.getCause()).hasToString("com.naraesigning.realtime.SnapshotUnavailableException");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("SSE snapshot contract invocation failed", exception);
        }
    }

    private void assertFinalPngCode(String code) {
        try {
            finalPngRead.invoke(finalPngRepository, ownerId, boardId);
            fail("final PNG must be unavailable");
        } catch (InvocationTargetException exception) {
            assertThat(exception.getCause()).hasMessage(code);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("final PNG contract invocation failed", exception);
        }
    }

    private Optional<?> publicLink() {
        try {
            var repositoryClass = Class.forName("com.naraesigning.board.core.JdbcBoardRepository");
            var constructor = repositoryClass.getDeclaredConstructor(JdbcOperations.class);
            constructor.setAccessible(true);
            var repository = constructor.newInstance(jdbc);
            var method = repositoryClass.getDeclaredMethod("findPublicByLookupHash", byte[].class);
            method.setAccessible(true);
            return (Optional<?>) method.invoke(repository, (Object) new byte[32]);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("public-link contract invocation failed", exception);
        }
    }

    private SignerSessionContract.State signerState() {
        var signer = new SignerSessionContract.Value(
                boardId, UUID.randomUUID(), 1, 0, 1.0, Instant.parse("2026-08-22T00:00:00Z"));
        var status = state.boardStatusRow().orElse("UNAVAILABLE");
        return SignerSessionContract.validate(signer,
                new SignerSessionContract.CurrentState(1, 0, 1.0, status));
    }

    private static BoardOwner owner(UUID ownerId) {
        var session = new MockHttpSession();
        AdminSessionContract.issue(session, ownerId, Instant.parse("2026-08-22T00:00:00Z"));
        return BoardOwner.fromSession(session);
    }

    private static BoardService boardService(JdbcOperations jdbc, VersionedCryptoService crypto)
            throws ReflectiveOperationException {
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

    @SuppressWarnings("unchecked")
    private JdbcOperations statefulJdbc() {
        var operations = mock(JdbcOperations.class);
        when(operations.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            requireDeletingExclusion(invocation.getArgument(0, String.class));
            return List.of();
        });
        when(operations.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    var sql = invocation.getArgument(0, String.class);
                    var result = mock(ResultSet.class);
                    if (sql.contains("left join background_asset")) {
                        var persistedStatus = state.boardStatusRow();
                        var exists = persistedStatus.isPresent();
                        when(result.next()).thenReturn(exists);
                        if (exists) {
                            when(result.getObject("owner_id", UUID.class)).thenReturn(ownerId);
                            when(result.getString("status")).thenReturn(persistedStatus.orElseThrow());
                            when(result.getInt("canvas_width")).thenReturn(1920);
                            when(result.getInt("canvas_height")).thenReturn(1080);
                        }
                    } else {
                        requireDeletingExclusion(sql);
                        when(result.next()).thenReturn(false);
                    }
                    return ((ResultSetExtractor<Object>) invocation.getArgument(1)).extractData(result);
                });
        return operations;
    }

    private static void requireDeletingExclusion(String sql) {
        if (!sql.toUpperCase(java.util.Locale.ROOT).contains("STATUS <> 'DELETING'")) {
            throw new AssertionError("DELETING row leaked through current read contract");
        }
    }
}
