package com.naraesigning.slot;

import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.Answers;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

final class CapturingJdbcSlotFixture {
    private final UUID boardId;
    private final CanvasSize canvas;
    private final Map<UUID, Slot> slots = new LinkedHashMap<>();
    private final List<String> lockTrace = new ArrayList<>();
    private final CapturingTransactions transactions = new CapturingTransactions();
    private final JdbcOperations jdbc = mock(JdbcOperations.class, this::answer);
    private int attemptedWrites;
    private int failOnWrite;

    CapturingJdbcSlotFixture(UUID boardId, CanvasSize canvas, List<Slot> slots) {
        this.boardId = boardId;
        this.canvas = canvas;
        slots.forEach(slot -> this.slots.put(slot.id(), slot));
    }

    JdbcSlotRepository repository() {
        return new JdbcSlotRepository(jdbc, transactions);
    }

    List<String> lockTrace() {
        return List.copyOf(lockTrace);
    }

    List<WriteIntent> committedWrites() {
        return transactions.committedWrites();
    }

    int attemptedWrites() {
        return attemptedWrites;
    }

    void failOnWrite(int number) {
        failOnWrite = number;
    }

    private Object answer(InvocationOnMock invocation) throws Throwable {
        if (invocation.getMethod().getName().equals("query")) {
            var sql = normalized(invocation.getArgument(0, String.class));
            var parameters = parameters(invocation);
            if (sql.contains("select canvas_width, canvas_height from board")) {
                lockTrace.add("board:" + parameters.get(0));
                return canvas;
            }
            if (sql.contains("select s.id from signature_slot")) {
                lockTrace.add("slot-list:" + sql);
                return slots.keySet().stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
            }
            if (sql.contains("for update of s, r")) {
                var slotId = (UUID) parameters.get(1);
                lockTrace.add("slot:" + slotId);
                return slots.get(slotId);
            }
        }
        if (invocation.getMethod().getName().equals("update")) {
            attemptedWrites++;
            if (attemptedWrites == failOnWrite) throw new IllegalStateException("injected JDBC write failure");
            var intent = new WriteIntent(normalized(invocation.getArgument(0, String.class)), parameters(invocation));
            transactions.record(intent);
            return 1;
        }
        return Answers.RETURNS_DEFAULTS.answer(invocation);
    }

    private static List<Object> parameters(InvocationOnMock invocation) {
        var arguments = invocation.getArguments();
        var firstParameter = invocation.getMethod().getName().equals("update") ? 1 : 2;
        if (arguments.length <= firstParameter) return List.of();
        var parameters = new ArrayList<Object>();
        for (int index = firstParameter; index < arguments.length; index++) {
            var argument = arguments[index];
            if (argument instanceof Object[] values) parameters.addAll(Arrays.asList(values));
            else parameters.add(argument);
        }
        return parameters;
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    record WriteIntent(String sql, List<Object> parameters) {}

    private static final class CapturingTransactions implements TransactionOperations {
        private final List<WriteIntent> committed = new ArrayList<>();
        private List<WriteIntent> pending;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            pending = new ArrayList<>();
            try {
                var result = action.doInTransaction(mock(TransactionStatus.class));
                committed.addAll(pending);
                return result;
            } catch (RuntimeException failure) {
                pending.clear();
                throw failure;
            } finally {
                pending = null;
            }
        }

        void record(WriteIntent intent) {
            if (pending == null) throw new IllegalStateException("write outside transaction");
            pending.add(intent);
        }

        List<WriteIntent> committedWrites() {
            return List.copyOf(committed);
        }
    }
}
