package com.naraesigning.signature;

import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.VersionedCryptoService;
import java.time.Instant;

final class SignatureSubmitService {
    private final SignatureSubmissionRepository repository;
    private final VersionedCryptoService crypto;
    private final SignatureSubmissionEvents events;

    SignatureSubmitService(
            SignatureSubmissionRepository repository,
            VersionedCryptoService crypto,
            SignatureSubmissionEvents events) {
        this.repository = repository;
        this.crypto = crypto;
        this.events = events;
    }

    void submit(SignatureSession session, SignaturePayload payload, Instant now) {
        if (!session.active()) throw SignatureSubmitException.sessionExpired();
        var signer = session.value();
        repository.withBoardThenSlotLocked(signer.boardId(), signer.slotId(), (state, writer) -> {
            validate(signer, state);
            var encrypted = crypto.encrypt(payload.canonicalBytes(),
                    CryptoContext.field("signature-slot", signer.slotId().toString(), "strokes"));
            writer.save(encrypted, now);
            events.afterCommit(new SignatureSubmitted(signer.boardId(), signer.slotId(), now));
            return null;
        });
    }

    private static void validate(
            com.naraesigning.session.SignerSessionContract.Value signer,
            SignatureSubmissionRepository.LockedState state) {
        var metadataCount = (state.ciphertextPresent() ? 1 : 0)
                + (state.noncePresent() ? 1 : 0)
                + (state.keyVersionPresent() ? 1 : 0)
                + (state.submittedAt() == null ? 0 : 1);
        if ((metadataCount == 0 && state.rosterSubmitted())
                || (metadataCount > 0 && metadataCount < 4)
                || (metadataCount == 4 && !state.rosterSubmitted())) {
            throw SignatureSubmitException.invalidState();
        }
        if (metadataCount == 4) throw SignatureSubmitException.alreadySubmitted();
        if ("CLOSED".equals(state.boardStatus())) throw SignatureSubmitException.closed();
        if (!"OPEN".equals(state.boardStatus())
                || !signer.boardId().equals(state.boardId())
                || !signer.slotId().equals(state.slotId())
                || signer.shareLinkVersion() != state.linkVersion()
                || signer.slotRevision() != state.revision()
                || Double.compare(signer.signatureAspectRatio(), state.aspect()) != 0
                || !"PLACED".equals(state.placementStatus())) {
            throw SignatureSubmitException.stale();
        }
    }
}
