package com.naraesigning.signature;

import com.naraesigning.realtime.DraftDelta;
import com.naraesigning.realtime.LiveSignatureRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

final class LiveSignatureService {
    static final Duration LEASE_DURATION = Duration.ofSeconds(90);
    private final SignatureSubmissionRepository repository;
    private final LiveSignatureRegistry drafts;

    LiveSignatureService(SignatureSubmissionRepository repository, LiveSignatureRegistry drafts) {
        this.repository = repository;
        this.drafts = drafts;
    }

    LiveSignatureRegistry.Version update(
            SignatureSession session, UUID claimId, SignaturePayload payload, Instant now) {
        if (!session.active() || claimId == null) throw SignatureSubmitException.sessionExpired();
        var signer = session.value();
        var expectedDraftEpoch = drafts.draftEpoch(signer.boardId(), signer.slotId());
        var updated = repository.withBoardThenSlotLocked(signer.boardId(), signer.slotId(), (state, writer) -> {
            SignatureSubmitService.validate(signer, state);
            SignatureSubmitService.validateClaim(state, claimId, now);
            var expiresAt = now.plus(LEASE_DURATION);
            writer.renewClaim(claimId, expiresAt);
            return new UpdatedDraft(signer.boardId(), signer.slotId(), claimId, expiresAt);
        });
        try {
            return drafts.update(updated.boardId(), updated.slotId(), updated.claimId(),
                    payload.canonicalBytes(), updated.expiresAt(), expectedDraftEpoch);
        } catch (LiveSignatureRegistry.OutOfSyncException exception) {
            throw SignatureSubmitException.draftOutOfSync();
        }
    }

    LiveSignatureRegistry.DeltaResult delta(
            SignatureSession session, UUID claimId, DraftDelta delta, Instant now) {
        if (!session.active() || claimId == null) throw SignatureSubmitException.sessionExpired();
        var signer = session.value();
        try {
            return drafts.apply(signer.boardId(), signer.slotId(), claimId, delta, () ->
                    repository.withBoardThenSlotLocked(signer.boardId(), signer.slotId(), (state, writer) -> {
                        SignatureSubmitService.validate(signer, state);
                        if (!claimId.equals(state.activeSignerClaim())
                                || state.activeSignerClaimExpiresAt() == null
                                || !state.activeSignerClaimExpiresAt().isAfter(now)) {
                            throw SignatureSubmitException.draftOutOfSync();
                        }
                        return state.activeSignerClaimExpiresAt();
                    }));
        } catch (LiveSignatureRegistry.OutOfSyncException exception) {
            throw SignatureSubmitException.draftOutOfSync();
        } catch (LiveSignatureRegistry.InvalidDeltaException exception) {
            throw SignatureSubmitException.invalidPayload();
        }
    }

    void cancel(SignatureSession session, UUID claimId) {
        if (!session.active()) throw SignatureSubmitException.sessionExpired();
        if (claimId == null) return;
        var signer = session.value();
        repository.withBoardThenSlotLocked(signer.boardId(), signer.slotId(), (state, writer) -> {
            if (claimId.equals(state.activeSignerClaim())) writer.releaseClaim(claimId);
            return null;
        });
        drafts.cancel(signer.boardId(), signer.slotId(), claimId);
    }

    void clearDraft(SignatureSession session, UUID claimId, Instant now) {
        if (!session.active()) throw SignatureSubmitException.sessionExpired();
        if (claimId == null) return;
        var signer = session.value();
        var cleared = repository.withBoardThenSlotLocked(signer.boardId(), signer.slotId(), (state, writer) -> {
            SignatureSubmitService.validate(signer, state);
            SignatureSubmitService.validateClaim(state, claimId, now);
            if (!claimId.equals(state.activeSignerClaim())) return false;
            writer.renewClaim(claimId, now.plus(LEASE_DURATION));
            return true;
        });
        if (cleared) {
            drafts.clear(signer.boardId(), signer.slotId(), claimId, now.plus(LEASE_DURATION));
        }
    }

    private record UpdatedDraft(UUID boardId, UUID slotId, UUID claimId, Instant expiresAt) {}
}
