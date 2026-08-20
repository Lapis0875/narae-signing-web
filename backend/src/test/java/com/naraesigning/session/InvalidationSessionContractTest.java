package com.naraesigning.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.Session;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class InvalidationSessionContractTest {
    @Test
    void refusesNotificationOutsideTransaction() {
        // Given
        var publisher = new AdminSessionInvalidationPublisher(mock(JdbcOperations.class), new ObjectMapper());
        var event = event();

        // When / Then
        assertThatIllegalStateException().isThrownBy(() -> publisher.publish(event));
    }

    @Test
    void publishesDatabasePrivatePayloadInsideTransaction() throws Exception {
        // Given
        var jdbc = mock(JdbcOperations.class);
        var mapper = new ObjectMapper();
        var publisher = new AdminSessionInvalidationPublisher(jdbc, mapper);
        var event = event();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try {
            // When
            publisher.publish(event);

            // Then
            var payload = mapper.writeValueAsString(event);
            verify(jdbc).query(
                    eq("select pg_notify('narae_admin_session_invalidated', ?)") ,
                    org.mockito.ArgumentMatchers.<ResultSetExtractor<Void>>any(),
                    eq(payload));
            assertThat(payload).doesNotContain("identity", "coordinates", "token");
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void passwordResetDeletesAndPublishesEveryIndexedAdminSession() {
        // Given
        @SuppressWarnings("unchecked")
        var sessions = (FindByIndexNameSessionRepository<Session>) mock(FindByIndexNameSessionRepository.class);
        var publisher = mock(AdminSessionInvalidationPublisher.class);
        var adminUserId = UUID.randomUUID();
        when(sessions.findByIndexNameAndIndexValue(
                        FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, adminUserId.toString()))
                .thenReturn(Map.of("first", new MapSession(), "second", new MapSession()));
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try {
            // When
            var deleted = new AdminSessionInvalidator(sessions, publisher).resetAll(adminUserId);

            // Then
            assertThat(deleted).isEqualTo(2);
            verify(sessions).deleteById("first");
            verify(sessions).deleteById("second");
            verify(publisher).publish(new AdminSessionInvalidated(
                    "first", adminUserId, AdminSessionInvalidated.Reason.PASSWORD_RESET));
            verify(publisher).publish(new AdminSessionInvalidated(
                    "second", adminUserId, AdminSessionInvalidated.Reason.PASSWORD_RESET));
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    private static AdminSessionInvalidated event() {
        return new AdminSessionInvalidated(
                "session-id", UUID.randomUUID(), AdminSessionInvalidated.Reason.LOGOUT);
    }
}
