package com.naraesigning.signer.identify;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

interface PublicIdentifyRepository {
    Optional<LinkRecord> findLink(UUID boardId, int linkVersion);

    Optional<SignerRecord> findSigner(UUID boardId, int linkVersion, byte[] identityHmac);

    Optional<SignerRecord> findSigner(UUID boardId, UUID slotId);
}

record LinkRecord(UUID boardId, String title, String status, int linkVersion, byte[] lookupHash) {
    LinkRecord {
        lookupHash = lookupHash.clone();
    }

    @Override
    public byte[] lookupHash() {
        return lookupHash.clone();
    }
}

record SignerRecord(
        UUID boardId,
        UUID slotId,
        String boardStatus,
        int linkVersion,
        boolean submitted,
        String placementStatus,
        long slotRevision,
        BigDecimal slotWidth,
        BigDecimal slotHeight,
        int canvasWidth,
        int canvasHeight) {}
