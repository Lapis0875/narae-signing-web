package com.naraesigning.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class ExpirySessionContractTest {
    private static final Instant ISSUED_AT = Instant.parse("2026-08-20T00:00:00Z");
    private static final Duration SIGNER_SESSION_MAXIMUM_LIFETIME = Duration.ofHours(2);

    @Test
    void adminExpiresAtAbsoluteTwelveHourBoundaryDespiteActivity() {
        // Given
        var session = new MockHttpSession();
        AdminSessionContract.issue(session, UUID.randomUUID(), ISSUED_AT);

        // When / Then
        assertThat(AdminSessionContract.isCurrent(session, ISSUED_AT.plusSeconds(43_199))).isTrue();
        assertThat(AdminSessionContract.isCurrent(session, ISSUED_AT.plusSeconds(43_200))).isFalse();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(43_200);
    }

    @Test
    void signerExpiresAtConfiguredTwoHourBoundary() {
        // Given
        var session = new MockHttpSession();
        session.setAttribute("identity", "must-be-removed");
        session.setAttribute("coordinates", "must-be-removed");
        var signer = fixture();
        SignerSessionContract.issue(session, signer, SIGNER_SESSION_MAXIMUM_LIFETIME);

        // When / Then
        assertThat(SignerSessionContract.isCurrent(session, ISSUED_AT.plusSeconds(7_199))).isTrue();
        assertThat(SignerSessionContract.isCurrent(session, ISSUED_AT.plusSeconds(7_200))).isFalse();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(7_200);
    }

    @Test
    void signerStoresOnlyPermittedFields() {
        // Given
        var session = new MockHttpSession();
        session.setAttribute("identity", "must-be-removed");
        session.setAttribute("coordinates", "must-be-removed");

        // When
        SignerSessionContract.issue(session, fixture(), SIGNER_SESSION_MAXIMUM_LIFETIME);

        // Then
        assertThat(session.getAttributeNames().asIterator())
                .toIterable()
                .containsExactlyInAnyOrderElementsOf(Set.of(
                        "signer.boardId",
                        "signer.slotId",
                        "signer.shareLinkVersion",
                        "signer.slotRevision",
                        "signer.signatureAspectRatio",
                        "signer.issuedAt"));
    }

    @Test
    void invalidationMatrixDistinguishesStaleClosedAndCurrentSigner() {
        // Given
        var signer = fixture();

        // When / Then
        assertThat(SignerSessionContract.validate(signer, new SignerSessionContract.CurrentState(
                        signer.shareLinkVersion() + 1, signer.slotRevision(), signer.signatureAspectRatio(), "OPEN")))
                .isEqualTo(SignerSessionContract.State.STALE_LINK);
        assertThat(SignerSessionContract.validate(signer, new SignerSessionContract.CurrentState(
                        signer.shareLinkVersion(), signer.slotRevision() + 1, signer.signatureAspectRatio(), "OPEN")))
                .isEqualTo(SignerSessionContract.State.STALE_SLOT);
        assertThat(SignerSessionContract.validate(signer, new SignerSessionContract.CurrentState(
                        signer.shareLinkVersion(), signer.slotRevision(), 1.5, "OPEN")))
                .isEqualTo(SignerSessionContract.State.STALE_ASPECT);
        assertThat(SignerSessionContract.validate(signer, new SignerSessionContract.CurrentState(
                        signer.shareLinkVersion(), signer.slotRevision(), signer.signatureAspectRatio(), "CLOSED")))
                .isEqualTo(SignerSessionContract.State.CLOSED);
        assertThat(SignerSessionContract.validate(signer, new SignerSessionContract.CurrentState(
                        signer.shareLinkVersion(), signer.slotRevision(), signer.signatureAspectRatio(), "DELETING")))
                .isEqualTo(SignerSessionContract.State.UNAVAILABLE);
        assertThat(SignerSessionContract.validate(signer, new SignerSessionContract.CurrentState(
                        signer.shareLinkVersion(), signer.slotRevision(), signer.signatureAspectRatio(), "OPEN")))
                .isEqualTo(SignerSessionContract.State.CURRENT);
        assertThat(SignerSessionContract.State.STALE_LINK.clearsSignerSession()).isTrue();
        assertThat(SignerSessionContract.State.STALE_SLOT.clearsSignerSession()).isTrue();
        assertThat(SignerSessionContract.State.STALE_ASPECT.clearsSignerSession()).isTrue();
        assertThat(SignerSessionContract.State.UNAVAILABLE.clearsSignerSession()).isTrue();
        assertThat(SignerSessionContract.State.CLOSED.clearsSignerSession()).isFalse();
    }

    private static SignerSessionContract.Value fixture() {
        return new SignerSessionContract.Value(
                UUID.randomUUID(), UUID.randomUUID(), 2, 7, 2.0, ISSUED_AT);
    }
}
