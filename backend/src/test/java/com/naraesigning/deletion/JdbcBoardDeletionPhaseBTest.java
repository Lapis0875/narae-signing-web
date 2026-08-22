package com.naraesigning.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.naraesigning.crypto.VersionedCryptoService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionOperations;

class JdbcBoardDeletionPhaseBTest {
    @Test
    void givenPersistedGraphAndTwoJobsWhenProductionPhaseBRunsThenEveryJobMustBeComplete() {
        // Given
        var state = new RelationalState();
        var jdbc = jdbc(state);
        var store = new JdbcBoardDeletionStore(jdbc, TransactionOperations.withoutTransaction(),
                new VersionedCryptoService(Map.of(1, new byte[32]), 1));
        state.completeFirstJob();

        // When
        store.finalizeReadyBoards();

        // Then
        assertThat(state.graphRowCount()).isEqualTo(4);
        assertThat(state.jobRowCount()).isEqualTo(2);

        // When
        state.completeSecondJob();
        store.finalizeReadyBoards();

        // Then
        assertThat(state.graphRowCount()).isZero();
        assertThat(state.jobRowCount()).isZero();
    }

    @SuppressWarnings("unchecked")
    private static JdbcOperations jdbc(RelationalState state) {
        var jdbc = mock(JdbcOperations.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            var sql = invocation.getArgument(0, String.class).toLowerCase(java.util.Locale.ROOT);
            var guardsIncompleteJobs = sql.contains("j.status <> 'completed'");
            return guardsIncompleteJobs && state.allJobsComplete() ? List.of(state.boardId) : List.of();
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            var sql = invocation.getArgument(0, String.class).toLowerCase(java.util.Locale.ROOT);
            if (sql.startsWith("delete from board_deletion_job")) state.jobs.clear();
            if (sql.startsWith("delete from board where")) state.graph.clear();
            return 1;
        });
        return jdbc;
    }

    private static final class RelationalState {
        private final UUID boardId = UUID.randomUUID();
        private final Set<String> graph = new LinkedHashSet<>(List.of("board", "roster", "slot", "background"));
        private final List<String> jobs = new java.util.ArrayList<>(List.of("PENDING", "PENDING"));

        void completeFirstJob() { jobs.set(0, "COMPLETED"); }
        void completeSecondJob() { jobs.set(1, "COMPLETED"); }
        boolean allJobsComplete() { return jobs.stream().allMatch("COMPLETED"::equals); }
        int graphRowCount() { return graph.size(); }
        int jobRowCount() { return jobs.size(); }
    }
}
