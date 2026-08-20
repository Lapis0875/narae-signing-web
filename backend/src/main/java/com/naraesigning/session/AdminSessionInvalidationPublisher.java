package com.naraesigning.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class AdminSessionInvalidationPublisher {
    static final String CHANNEL = "narae_admin_session_invalidated";
    private final JdbcOperations jdbc;
    private final ObjectMapper objectMapper;

    public AdminSessionInvalidationPublisher(JdbcOperations jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void publish(AdminSessionInvalidated event) {
        requireTransaction();
        try {
            jdbc.query(
                    "select pg_notify('" + CHANNEL + "', ?)",
                    (ResultSetExtractor<Void>) resultSet -> null,
                    objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot encode administrator session invalidation", exception);
        }
    }

    static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Administrator session invalidation requires a database transaction");
        }
    }
}
