package com.naraesigning.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.naraesigning.realtime.LiveSignatureRegistry;
import com.naraesigning.session.SignerSessionContract;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveSignatureServiceTest {
    private static final UUID BOARD_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SLOT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ROSTER_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    @Test
    void keepsASecondSignerOutUntilTheCurrentDraftLeaseEndsOrIsReleased() {
        var repository = new MemoryRepository();
        var drafts = mock(LiveSignatureRegistry.class);
        var service = new LiveSignatureService(repository, drafts);
        var firstClaim = UUID.randomUUID();

        service.update(session(), firstClaim, payload(), NOW);

        assertThat(repository.state.activeSignerClaim()).isEqualTo(firstClaim);
        assertThat(repository.state.activeSignerClaimExpiresAt())
                .isEqualTo(NOW.plus(LiveSignatureService.LEASE_DURATION));
        assertThatThrownBy(() -> service.update(session(), UUID.randomUUID(), payload(), NOW))
                .isInstanceOfSatisfying(SignatureSubmitException.class,
                        exception -> assertThat(exception.code()).isEqualTo("signature_in_progress"));
        verify(drafts).update(eq(BOARD_ID), eq(SLOT_ID), eq(firstClaim), any(byte[].class),
                eq(NOW.plus(LiveSignatureService.LEASE_DURATION)));
    }

    @Test
    void releasesOnlyTheCurrentSessionClaimWhenSigningIsCancelled() {
        var repository = new MemoryRepository();
        var drafts = mock(LiveSignatureRegistry.class);
        var service = new LiveSignatureService(repository, drafts);
        var claim = UUID.randomUUID();
        service.update(session(), claim, payload(), NOW);

        service.cancel(session(), claim);

        assertThat(repository.state.activeSignerClaim()).isNull();
        assertThat(repository.state.activeSignerClaimExpiresAt()).isNull();
        verify(drafts).clear(BOARD_ID, SLOT_ID, claim);
    }

    @Test
    void clearsTheVisibleDraftButKeepsTheCurrentSignerLease() {
        var repository = new MemoryRepository();
        var drafts = mock(LiveSignatureRegistry.class);
        var service = new LiveSignatureService(repository, drafts);
        var claim = UUID.randomUUID();
        service.update(session(), claim, payload(), NOW);

        service.clearDraft(session(), claim, NOW.plusSeconds(10));

        assertThat(repository.state.activeSignerClaim()).isEqualTo(claim);
        assertThat(repository.state.activeSignerClaimExpiresAt())
                .isEqualTo(NOW.plusSeconds(10).plus(LiveSignatureService.LEASE_DURATION));
        verify(drafts).clear(BOARD_ID, SLOT_ID, claim);
    }

    private static SignatureSession session() {
        return new SignatureSession(new SignerSessionContract.Value(
                BOARD_ID, SLOT_ID, 3, 11, 1.5, NOW.minusSeconds(60)), true);
    }

    private static SignaturePayload payload() {
        return new SignaturePayload("{\"version\":1,\"strokes\":[{\"points\":[{\"x\":1,\"y\":2}]}]}"
                .getBytes(StandardCharsets.UTF_8), java.util.List.of());
    }

    private static final class MemoryRepository implements SignatureSubmissionRepository {
        private LockedState state = new LockedState(
                BOARD_ID, SLOT_ID, ROSTER_ID, "OPEN", 3, "PLACED", 11, 1.5,
                false, false, false, false, null);

        @Override
        public <T> T withBoardThenSlotLocked(UUID boardId, UUID slotId, LockedAction<T> action) {
            return action.apply(state, new SubmissionWriter() {
                @Override
                public void save(com.naraesigning.crypto.EncryptedValue encrypted, Instant submittedAt) {}

                @Override
                public void renewClaim(UUID claimId, Instant expiresAt) {
                    state = new LockedState(
                            state.boardId(), state.slotId(), state.rosterEntryId(), state.boardStatus(),
                            state.linkVersion(), state.placementStatus(), state.revision(), state.aspect(),
                            state.rosterSubmitted(), state.ciphertextPresent(), state.noncePresent(),
                            state.keyVersionPresent(), state.submittedAt(), claimId, expiresAt);
                }

                @Override
                public void releaseClaim(UUID claimId) {
                    if (claimId.equals(state.activeSignerClaim())) {
                        state = new LockedState(
                                state.boardId(), state.slotId(), state.rosterEntryId(), state.boardStatus(),
                                state.linkVersion(), state.placementStatus(), state.revision(), state.aspect(),
                                state.rosterSubmitted(), state.ciphertextPresent(), state.noncePresent(),
                                state.keyVersionPresent(), state.submittedAt(), null, null);
                    }
                }
            });
        }
    }
}
