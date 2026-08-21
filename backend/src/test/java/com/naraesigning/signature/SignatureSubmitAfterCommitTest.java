package com.naraesigning.signature;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SignatureSubmitAfterCommitTest {
    @Test
    void publishesOnlyAfterTheTransactionCommits() {
        // Given
        var publisher = mock(ApplicationEventPublisher.class);
        var events = new AfterCommitSignatureSubmissionEvents(publisher);
        var event = new SignatureSubmitted(UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        try {
            // When
            events.afterCommit(event);

            // Then
            verifyNoInteractions(publisher);
            TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
            verify(publisher).publishEvent(event);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void refusesToPublishOutsideATransaction() {
        // Given
        var events = new AfterCommitSignatureSubmissionEvents(mock(ApplicationEventPublisher.class));
        var event = new SignatureSubmitted(UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH);

        // When / Then
        assertThatIllegalStateException().isThrownBy(() -> events.afterCommit(event));
    }
}
