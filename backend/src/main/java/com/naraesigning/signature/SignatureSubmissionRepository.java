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

    @FunctionalInterface
    interface SubmissionWriter {
        void save(EncryptedValue encrypted, Instant submittedAt);
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
            Instant submittedAt) {
        LockedState withStatus(String value) {
            return new LockedState(boardId, slotId, rosterEntryId, value, linkVersion,
                    placementStatus, revision, aspect, rosterSubmitted, ciphertextPresent,
                    noncePresent, keyVersionPresent, submittedAt);
        }

        LockedState withLinkVersion(int value) {
            return new LockedState(boardId, slotId, rosterEntryId, boardStatus, value,
                    placementStatus, revision, aspect, rosterSubmitted, ciphertextPresent,
                    noncePresent, keyVersionPresent, submittedAt);
        }

        LockedState withRevision(long value) {
            return new LockedState(boardId, slotId, rosterEntryId, boardStatus, linkVersion,
                    placementStatus, value, aspect, rosterSubmitted, ciphertextPresent,
                    noncePresent, keyVersionPresent, submittedAt);
        }

        LockedState withAspect(double value) {
            return new LockedState(boardId, slotId, rosterEntryId, boardStatus, linkVersion,
                    placementStatus, revision, value, rosterSubmitted, ciphertextPresent,
                    noncePresent, keyVersionPresent, submittedAt);
        }

        LockedState withPlacement(String value) {
            return new LockedState(boardId, slotId, rosterEntryId, boardStatus, linkVersion,
                    value, revision, aspect, rosterSubmitted, ciphertextPresent,
                    noncePresent, keyVersionPresent, submittedAt);
        }

        LockedState withRosterSubmitted(boolean value) {
            return new LockedState(boardId, slotId, rosterEntryId, boardStatus, linkVersion,
                    placementStatus, revision, aspect, value, ciphertextPresent,
                    noncePresent, keyVersionPresent, submittedAt);
        }

        LockedState withCiphertextState(
                boolean ciphertext, boolean nonce, boolean keyVersion, Instant at) {
            return new LockedState(boardId, slotId, rosterEntryId, boardStatus, linkVersion,
                    placementStatus, revision, aspect, rosterSubmitted, ciphertext,
                    nonce, keyVersion, at);
        }
    }
}
