package com.naraesigning.signature;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class AfterCommitSignatureSubmissionEvents implements SignatureSubmissionEvents {
    private final ApplicationEventPublisher events;

    AfterCommitSignatureSubmissionEvents(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void afterCommit(SignatureSubmitted event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Signature events require an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                events.publishEvent(event);
            }
        });
    }
}
