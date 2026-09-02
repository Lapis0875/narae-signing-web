package com.naraesigning.signature;

import com.naraesigning.crypto.EncryptedValue;
import java.time.Instant;
import java.util.UUID;

interface SignatureSubmissionRepository {
    <T> T withBoardThenSlotLocked(UUID boardId, UUID slotId, LockedAction<T> action);

    @FunctionalInterface
    interface LockedAction<T> {
        T apply(LockedState state, SubmissionWriter writer);
    }

    interface SubmissionWriter {
        void save(EncryptedValue encrypted, Instant submittedAt);

        default void renewClaim(UUID claimId, Instant expiresAt) {
            throw new UnsupportedOperationException();
        }

        default void releaseClaim(UUID claimId) {
            throw new UnsupportedOperationException();
        }
    }

    record LockedState(
            UUID boardId,
            UUID slotId,
            UUID rosterEntryId,
            String boardStatus,
            int linkVersion,
            String placementStatus,
            long revision,
            double aspect,
            boolean rosterSubmitted,
            boolean ciphertextPresent,
            boolean noncePresent,
            boolean keyVersionPresent,
            Instant submittedAt,
            UUID activeSignerClaim,
            Instant activeSignerClaimExpiresAt) {
        LockedState(
                UUID boardId,
                UUID slotId,
                UUID rosterEntryId,
                String boardStatus,
                int linkVersion,
                String placementStatus,
                long revision,
                double aspect,
                boolean rosterSubmitted,
                boolean ciphertextPresent,
                boolean noncePresent,
                boolean keyVersionPresent,
                Instant submittedAt) {
            this(boardId, slotId, rosterEntryId, boardStatus, linkVersion, placementStatus, revision,
                    aspect, rosterSubmitted, ciphertextPresent, noncePresent, keyVersionPresent,
                    submittedAt, null, null);
        }

        LockedState withStatus(String value) {
            return new LockedState(boardId, slotId, rosterEntryId, value, linkVersion,
                    placementStatus, revision, aspect, rosterSubmitted, ciphertextPresent,
                    noncePresent, keyVersionPresent, submittedAt,
                    activeSignerClaim, activeSignerClaimExpiresAt);
        }

        LockedState withLinkVersion(int value) {
            return new LockedState(boardId, slotId, rosterEntryId, boardStatus, value,
                    placementStatus, revision, aspect, rosterSubmitted, ciphertextPresent,
                    noncePresent, keyVersionPresent, submittedAt,
                    activeSignerClaim, activeSignerClaimExpiresAt);
        }

        LockedState withRevision(long value) {
            return new LockedState(boardId, slotId, rosterEntryId, boardStatus, linkVersion,
                    placementStatus, value, aspect, rosterSubmitted, ciphertextPresent,
                    noncePresent, keyVersionPresent, submittedAt,
                    activeSignerClaim, activeSignerClaimExpiresAt);
        }

        LockedState withAspect(double value) {
            return new LockedState(boardId, slotId, rosterEntryId, boardStatus, linkVersion,
                    placementStatus, revision, value, rosterSubmitted, ciphertextPresent,
                    noncePresent, keyVersionPresent, submittedAt,
                    activeSignerClaim, activeSignerClaimExpiresAt);
        }

        LockedState withPlacement(String value) {
            return new LockedState(boardId, slotId, rosterEntryId, boardStatus, linkVersion,
                    value, revision, aspect, rosterSubmitted, ciphertextPresent,
                    noncePresent, keyVersionPresent, submittedAt,
                    activeSignerClaim, activeSignerClaimExpiresAt);
        }

        LockedState withRosterSubmitted(boolean value) {
            return new LockedState(boardId, slotId, rosterEntryId, boardStatus, linkVersion,
                    placementStatus, revision, aspect, value, ciphertextPresent,
                    noncePresent, keyVersionPresent, submittedAt,
                    activeSignerClaim, activeSignerClaimExpiresAt);
        }

        LockedState withCiphertextState(
                boolean ciphertext, boolean nonce, boolean keyVersion, Instant at) {
            return new LockedState(boardId, slotId, rosterEntryId, boardStatus, linkVersion,
                    placementStatus, revision, aspect, rosterSubmitted, ciphertext,
                    nonce, keyVersion, at, activeSignerClaim, activeSignerClaimExpiresAt);
        }
    }
}
