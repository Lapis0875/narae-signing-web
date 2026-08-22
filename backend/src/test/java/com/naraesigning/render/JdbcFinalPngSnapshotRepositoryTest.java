package com.naraesigning.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.mockito.Answers;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.junit.jupiter.api.Test;

final class JdbcFinalPngSnapshotRepositoryTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SLOT = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test
    void readsNewLockedPlacementOnlyAfterClosedReopenChangeAndReclose() {
        var fixture = new LifecycleJdbc();
        var repository = new JdbcFinalPngSnapshotRepository(fixture.jdbc(), fixture.transactions());

        var firstClosed = repository.readClosed(OWNER, BOARD);
        fixture.reopenAndMove(new BigDecimal("0.62500000"));
        assertThatThrownBy(() -> repository.readClosed(OWNER, BOARD))
                .isInstanceOfSatisfying(FinalPngException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FINAL_PNG_NOT_CLOSED"));
        fixture.reclose();
        var currentClosed = repository.readClosed(OWNER, BOARD);

        assertThat(firstClosed.slots().getFirst().x()).isEqualByComparingTo("0.12500000");
        assertThat(currentClosed.slots().getFirst().x()).isEqualByComparingTo("0.62500000");
        assertThat(fixture.transactionCount).isEqualTo(3);
        assertThat(fixture.sqlTrace).filteredOn(sql -> sql.contains("for update of b")).hasSize(3);
        assertThat(fixture.sqlTrace).filteredOn(sql -> sql.contains("order by s.id for update of s")).hasSize(2);
    }

    private static final class LifecycleJdbc {
        private final List<String> sqlTrace = new ArrayList<>();
        private final JdbcOperations jdbc = mock(JdbcOperations.class, this::answer);
        private String status = "CLOSED";
        private BigDecimal x = new BigDecimal("0.12500000");
        private int transactionCount;

        JdbcOperations jdbc() { return jdbc; }

        TransactionOperations transactions() {
            return new TransactionOperations() {
                @Override public <T> T execute(TransactionCallback<T> action) {
                    transactionCount++;
                    return action.doInTransaction(mock(TransactionStatus.class));
                }
            };
        }

        void reopenAndMove(BigDecimal movedX) { status = "OPEN"; x = movedX; }
        void reclose() { status = "CLOSED"; }

        private Object answer(InvocationOnMock invocation) throws Throwable {
            if (!invocation.getMethod().getName().equals("query")) {
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            var sql = invocation.getArgument(0, String.class).replaceAll("\\s+", " ").trim();
            sqlTrace.add(sql);
            if (sql.contains("from board b")) {
                @SuppressWarnings("unchecked")
                var extractor = (ResultSetExtractor<FinalPngSnapshot>) invocation.getArgument(1);
                return extractor.extractData(boardRow());
            }
            @SuppressWarnings("unchecked")
            var mapper = (RowMapper<FinalPngEncryptedSlot>) invocation.getArgument(1);
            return List.of(mapper.mapRow(slotRow(), 0));
        }

        private ResultSet boardRow() throws Exception {
            var result = mock(ResultSet.class);
            when(result.next()).thenReturn(true);
            when(result.getObject("owner_id", UUID.class)).thenReturn(OWNER);
            when(result.getString("status")).thenReturn(status);
            when(result.getInt("canvas_width")).thenReturn(800);
            when(result.getInt("canvas_height")).thenReturn(600);
            when(result.getObject("background_id", UUID.class)).thenReturn(null);
            return result;
        }

        private ResultSet slotRow() throws Exception {
            var result = mock(ResultSet.class);
            when(result.getObject("id", UUID.class)).thenReturn(SLOT);
            when(result.getBigDecimal("x")).thenReturn(x);
            when(result.getBigDecimal("y")).thenReturn(new BigDecimal("0.25000000"));
            when(result.getBigDecimal("width")).thenReturn(new BigDecimal("0.25000000"));
            when(result.getBigDecimal("height")).thenReturn(new BigDecimal("0.25000000"));
            when(result.getString("background_color")).thenReturn("white");
            when(result.getBytes("encrypted_strokes")).thenReturn(null);
            return result;
        }
    }
}
