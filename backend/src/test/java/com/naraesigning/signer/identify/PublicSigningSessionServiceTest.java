package com.naraesigning.signer.identify;

import static org.assertj.core.api.Assertions.assertThat;

import com.naraesigning.session.SignerSessionContract;
import com.naraesigning.signature.SignatureSession;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublicSigningSessionServiceTest {
    private static final UUID BOARD_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SLOT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private SessionRepository repository;
    private PublicSigningSessionService service;

    @BeforeEach
    void setUp() {
        repository = new SessionRepository();
        service = new PublicSigningSessionService(repository);
    }

    @Test
    void returnsReadyWithTheExactCanonicalSessionAspect() {
        var result = service.read(session(3, 11, 1.5));

        assertThat(result.response().state()).isEqualTo(SigningSessionState.READY);
        assertThat(result.response().signatureAspectRatio()).isEqualTo(1.5);
        assertThat(result.clearSession()).isFalse();
    }

    @Test
    void returnsClosedOrSubmittedWithoutPrivateDataAndKeepsTheSession() {
        repository.status = "CLOSED";
        var closed = service.read(session(3, 11, 1.5));
        repository.submitted = true;
        var submitted = service.read(session(3, 11, 1.5));

        assertThat(closed.response().state()).isEqualTo(SigningSessionState.CLOSED);
        assertThat(closed.response().signatureAspectRatio()).isNull();
        assertThat(closed.clearSession()).isFalse();
        assertThat(submitted.response().state()).isEqualTo(SigningSessionState.SUBMITTED);
        assertThat(submitted.response().signatureAspectRatio()).isNull();
        assertThat(submitted.clearSession()).isFalse();
    }

    @Test
    void staleLinkRevisionAndAspectReturnReidentificationAndClearTheSession() {
        for (var signer : java.util.List.of(
                session(2, 11, 1.5),
                session(3, 10, 1.5),
                session(3, 11, 1.4))) {
            var result = service.read(signer);
            assertThat(result.response().state()).isEqualTo(SigningSessionState.STALE);
            assertThat(result.clearSession()).isTrue();
        }
    }

    @Test
    void unavailableOrMalformedCurrentStateFailsClosedAndClearsTheSession() {
        repository.status = "DELETING";
        var deleting = service.read(session(3, 11, 1.5));
        repository.status = "UNAVAILABLE";
        var unavailable = service.read(session(3, 11, 1.5));
        repository.status = "OPEN";
        repository.placement = "UNPLACED";
        var unplaced = service.read(session(3, 11, 1.5));
        repository.present = false;
        var missing = service.read(session(3, 11, 1.5));

        assertThat(java.util.List.of(deleting, unavailable, unplaced, missing)).allSatisfy(result -> {
            assertThat(result.response().state()).isEqualTo(SigningSessionState.INVALID);
            assertThat(result.clearSession()).isTrue();
        });
    }

    private static SignatureSession session(int linkVersion, long revision, double aspect) {
        return new SignatureSession(new SignerSessionContract.Value(
                BOARD_ID, SLOT_ID, linkVersion, revision, aspect, Instant.parse("2026-08-21T00:00:00Z")), true);
    }

    private static final class SessionRepository implements PublicIdentifyRepository {
        private boolean present = true;
        private boolean submitted;
        private String status = "OPEN";
        private String placement = "PLACED";

        @Override
        public Optional<LinkRecord> findLink(UUID boardId, int linkVersion) {
            return Optional.empty();
        }

        @Override
        public Optional<SignerRecord> findSigner(UUID boardId, int linkVersion, byte[] identityHmac) {
            return Optional.empty();
        }

        @Override
        public Optional<SignerRecord> findSigner(UUID boardId, UUID slotId) {
            if (!present || !BOARD_ID.equals(boardId) || !SLOT_ID.equals(slotId)) {
                return Optional.empty();
            }
            return Optional.of(new SignerRecord(
                    BOARD_ID, SLOT_ID, status, 3, submitted, placement, 11,
                    new BigDecimal("0.50"), new BigDecimal("0.50"), 1620, 1080));
        }
    }
}
