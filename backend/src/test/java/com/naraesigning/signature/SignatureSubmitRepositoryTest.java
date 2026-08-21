package com.naraesigning.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.naraesigning.crypto.EncryptedValue;
import com.naraesigning.crypto.VersionedCryptoService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class SignatureSubmitRepositoryTest {
    @Test
    void locksBoardThenSlotAndConditionallyWritesBothTables() throws Exception {
        // Given
        var boardId = UUID.randomUUID();
        var slotId = UUID.randomUUID();
        var rosterId = UUID.randomUUID();
        var jdbc = mock(JdbcOperations.class);
        var transactions = immediateTransactions();
        var resultSet = resultSet(boardId, slotId, rosterId);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any(),
                any(Object[].class))).thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    var extractor = (ResultSetExtractor<Object>) invocation.getArgument(1);
                    return extractor.extractData(resultSet);
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var captured = new AtomicReference<SignatureSubmissionRepository.LockedState>();
        var repository = new JdbcSignatureSubmissionRepository(jdbc, transactions);

        // When
        repository.withBoardThenSlotLocked(boardId, slotId, (state, writer) -> {
            captured.set(state);
            writer.save(new EncryptedValue(new byte[16], new byte[12], 7), Instant.EPOCH);
            return null;
        });

        // Then
        assertThat(captured.get()).extracting(
                SignatureSubmissionRepository.LockedState::boardStatus,
                SignatureSubmissionRepository.LockedState::linkVersion,
                SignatureSubmissionRepository.LockedState::aspect)
                .containsExactly("OPEN", 3, 1.5);
        var order = inOrder(jdbc);
        order.verify(jdbc).query(org.mockito.ArgumentMatchers.contains("from board"),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any(),
                org.mockito.ArgumentMatchers.eq(boardId));
        order.verify(jdbc).query(org.mockito.ArgumentMatchers.contains("from signature_slot"),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any(),
                org.mockito.ArgumentMatchers.eq(boardId), org.mockito.ArgumentMatchers.eq(slotId));
        order.verify(jdbc).update(org.mockito.ArgumentMatchers.contains(
                "encrypted_strokes is null"), any(byte[].class), any(byte[].class),
                org.mockito.ArgumentMatchers.eq(7), org.mockito.ArgumentMatchers.eq(Instant.EPOCH),
                org.mockito.ArgumentMatchers.eq(slotId));
        order.verify(jdbc).update(org.mockito.ArgumentMatchers.contains(
                "submitted = false"), org.mockito.ArgumentMatchers.eq(rosterId));
    }

    @Test
    void secondConditionalUpdateFailureRollsBackCiphertextRosterAndEvent() throws Exception {
        // Given
        var boardId = UUID.randomUUID();
        var slotId = UUID.randomUUID();
        var rosterId = UUID.randomUUID();
        var jdbc = mock(JdbcOperations.class);
        var resultSet = resultSet(boardId, slotId, rosterId);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any(),
                any(Object[].class))).thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    var extractor = (ResultSetExtractor<Object>) invocation.getArgument(1);
                    return extractor.extractData(resultSet);
                });
        var committedMutations = new ArrayList<String>();
        var rosterConditionalRows = new AtomicInteger(-1);
        var attemptedWrites = new AtomicInteger();
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            attemptedWrites.incrementAndGet();
            var sql = invocation.getArgument(0, String.class);
            if (sql.contains("update signature_slot")) {
                enlistCommit(committedMutations, "ciphertext");
                return 1;
            }
            if (sql.contains("update roster_entry")) {
                rosterConditionalRows.set(0);
                return 0;
            }
            throw new AssertionError("unexpected SQL");
        });
        var transactionManager = new SynchronizingTransactionManager();
        var publisher = mock(ApplicationEventPublisher.class);
        var service = new SignatureSubmitService(
                new JdbcSignatureSubmissionRepository(jdbc, new TransactionTemplate(transactionManager)),
                new VersionedCryptoService(Map.of(7, new byte[32]), 7),
                new AfterCommitSignatureSubmissionEvents(publisher));
        var session = new SignatureSession(new com.naraesigning.session.SignerSessionContract.Value(
                boardId, slotId, 3, 11, 1.5, Instant.EPOCH), true);
        var payload = new SignaturePayload(
                "{\"version\":1,\"strokes\":[]}".getBytes(StandardCharsets.UTF_8), java.util.List.of());

        // When / Then
        assertThatThrownBy(() -> service.submit(session, payload, Instant.EPOCH))
                .isInstanceOfSatisfying(SignatureSubmitException.class,
                        exception -> assertThat(exception.code()).isEqualTo("signature_state_invalid"));
        assertThat(attemptedWrites).hasValue(2);
        assertThat(transactionManager.commits).isZero();
        assertThat(transactionManager.rollbacks).isEqualTo(1);
        assertThat(rosterConditionalRows).hasValue(0);
        assertThat(committedMutations).isEmpty();
        verifyNoInteractions(publisher);
    }

    private static TransactionOperations immediateTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(mock(TransactionStatus.class));
            }
        };
    }

    private static void enlistCommit(List<String> committedMutations, String mutation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("JDBC write outside transaction synchronization");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                committedMutations.add(mutation);
            }
        });
    }

    private static ResultSet resultSet(UUID boardId, UUID slotId, UUID rosterId) throws Exception {
        var resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true, true);
        when(resultSet.getObject("id", UUID.class)).thenReturn(boardId);
        when(resultSet.getString("status")).thenReturn("OPEN");
        when(resultSet.getInt("share_link_version")).thenReturn(3);
        when(resultSet.getInt("canvas_width")).thenReturn(300);
        when(resultSet.getInt("canvas_height")).thenReturn(200);
        when(resultSet.getObject("board_id", UUID.class)).thenReturn(boardId);
        when(resultSet.getObject("slot_id", UUID.class)).thenReturn(slotId);
        when(resultSet.getObject("roster_entry_id", UUID.class)).thenReturn(rosterId);
        when(resultSet.getString("placement_status")).thenReturn("PLACED");
        when(resultSet.getLong("slot_revision")).thenReturn(11L);
        when(resultSet.getBigDecimal("width")).thenReturn(new BigDecimal("0.30"));
        when(resultSet.getBigDecimal("height")).thenReturn(new BigDecimal("0.30"));
        return resultSet;
    }

    private static final class SynchronizingTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
