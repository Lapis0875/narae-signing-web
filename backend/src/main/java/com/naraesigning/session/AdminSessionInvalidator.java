package com.naraesigning.session;

import java.util.UUID;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

public final class AdminSessionInvalidator {
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final AdminSessionInvalidationPublisher publisher;

    public AdminSessionInvalidator(
            FindByIndexNameSessionRepository<? extends Session> sessions,
            AdminSessionInvalidationPublisher publisher) {
        this.sessions = sessions;
        this.publisher = publisher;
    }

    public void logout(String sessionId, UUID adminUserId) {
        AdminSessionInvalidationPublisher.requireTransaction();
        sessions.deleteById(sessionId);
        publisher.publish(new AdminSessionInvalidated(
                sessionId, adminUserId, AdminSessionInvalidated.Reason.LOGOUT));
    }

    public int resetAll(UUID adminUserId) {
        AdminSessionInvalidationPublisher.requireTransaction();
        var indexed = sessions.findByIndexNameAndIndexValue(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, adminUserId.toString());
        indexed.keySet().forEach(sessionId -> {
            sessions.deleteById(sessionId);
            publisher.publish(new AdminSessionInvalidated(
                    sessionId, adminUserId, AdminSessionInvalidated.Reason.PASSWORD_RESET));
        });
        return indexed.size();
    }
}
