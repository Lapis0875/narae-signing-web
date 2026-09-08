package com.naraesigning.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.naraesigning.session.AdminSessionContract;
import com.naraesigning.realtime.LiveSignatureRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BoardDeletionControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");

    @Test
    void givenCurrentOwnerAndConfirmationWhenDeletingThenPhaseAReceivesExactOwner() {
        // Given
        var store = new CapturingStore();
        var request = request(NOW.minus(Duration.ofHours(11)));
        var ownerId = UUID.fromString(request.getSession().getAttribute(AdminSessionContract.ADMIN_USER_ID).toString());
        var boardId = UUID.randomUUID();

        // When
        var response = controller(store).delete(boardId, new BoardDeletionController.Confirmation(true), request);

        // Then
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(store.ownerId).isEqualTo(ownerId);
        assertThat(store.boardId).isEqualTo(boardId);
    }

    @Test
    void givenExpiredSessionWhenDeletingThenRequestIsDeniedAtAbsoluteBoundary() {
        // Given
        var store = new CapturingStore();
        var request = request(NOW.minus(AdminSessionContract.ABSOLUTE_LIFETIME));

        // When / Then
        assertThatThrownBy(() -> controller(store).delete(UUID.randomUUID(),
                new BoardDeletionController.Confirmation(true), request))
                .isInstanceOf(BoardDeletionException.class)
                .hasMessage("UNAUTHORIZED");
        assertThat(store.ownerId).isNull();
    }

    @Test
    void givenMissingConfirmationWhenDeletingThenNoStateChanges() {
        // Given
        var store = new CapturingStore();

        // When / Then
        assertThatThrownBy(() -> controller(store).delete(UUID.randomUUID(),
                new BoardDeletionController.Confirmation(false), request(NOW)))
                .isInstanceOf(BoardDeletionException.class)
                .hasMessage("DELETION_CONFIRMATION_REQUIRED");
        assertThat(store.ownerId).isNull();
    }

    private static BoardDeletionController controller(BoardDeletionStore store) {
        return new BoardDeletionController(new BoardDeletionService(
                store, event -> {}, mock(LiveSignatureRegistry.class)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MockHttpServletRequest request(Instant issuedAt) {
        var request = new MockHttpServletRequest();
        AdminSessionContract.issue(request.getSession(), UUID.randomUUID(), issuedAt);
        return request;
    }

    private static final class CapturingStore implements BoardDeletionStore {
        private UUID ownerId;
        private UUID boardId;

        @Override public boolean begin(UUID ownerId, UUID boardId) {
            this.ownerId = ownerId;
            this.boardId = boardId;
            return true;
        }
        @Override public List<DeletionJob> claim(UUID token, Instant now, Duration lease) { return List.of(); }
        @Override public boolean renew(UUID id, UUID token, Instant now, Duration lease) { return false; }
        @Override public void complete(UUID id, UUID token) {}
        @Override public void retry(UUID id, UUID token, Instant now, int attempt) {}
        @Override public void finalizeReadyBoards() {}
    }
}
