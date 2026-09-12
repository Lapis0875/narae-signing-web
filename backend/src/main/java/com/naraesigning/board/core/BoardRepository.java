package com.naraesigning.board.core;

import com.naraesigning.crypto.EncryptedValue;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface BoardRepository {
    StoredBoard create(NewBoard board);

    List<StoredBoard> list(BoardOwner owner);

    Optional<StoredBoard> find(BoardOwner owner, UUID boardId);

    Optional<StoredBoard> lock(BoardOwner owner, UUID boardId);

    Optional<StoredBoard> rename(BoardOwner owner, UUID boardId, BoardTitle title);

    Optional<StoredBoard> patch(BoardOwner owner, UUID boardId, BoardTitle title,
            SignatureInkColor signatureInkColor);

    Optional<StoredBoard> lockShare(BoardOwner owner, UUID boardId);

    Optional<StoredBoard> replaceShare(
            BoardOwner owner, UUID boardId, int expectedVersion, StoredShare share);

    Optional<StoredPublicBoardLink> findPublicByLookupHash(byte[] lookupHash);
}

record NewBoard(
        UUID id,
        BoardOwner owner,
        BoardTitle title,
        BoardStatus status,
        int canvasWidth,
        int canvasHeight,
        SignatureInkColor signatureInkColor,
        StoredShare share) {}

record StoredBoard(
        UUID id,
        BoardOwner owner,
        BoardTitle title,
        BoardStatus status,
        int canvasWidth,
        int canvasHeight,
        SignatureInkColor signatureInkColor,
        StoredShare share,
        Instant createdAt,
        Instant updatedAt) {
    StoredBoard withTitle(BoardTitle changedTitle) {
        return new StoredBoard(id, owner, changedTitle, status, canvasWidth, canvasHeight, signatureInkColor,
                share, createdAt, updatedAt);
    }

    StoredBoard withShare(StoredShare changedShare) {
        return new StoredBoard(id, owner, title, status, canvasWidth, canvasHeight, signatureInkColor,
                changedShare, createdAt, updatedAt);
    }

    StoredBoard withPatch(BoardTitle changedTitle, SignatureInkColor changedInkColor) {
        return new StoredBoard(id, owner, changedTitle, status, canvasWidth, canvasHeight, changedInkColor,
                share, createdAt, updatedAt);
    }

    StoredBoard withStatus(BoardStatus changedStatus) {
        return new StoredBoard(id, owner, title, changedStatus, canvasWidth, canvasHeight, signatureInkColor,
                share, createdAt, updatedAt);
    }
}

record StoredShare(int version, byte[] lookupHash, EncryptedValue encryptedToken) {
    StoredShare {
        lookupHash = lookupHash.clone();
    }

    @Override
    public byte[] lookupHash() {
        return lookupHash.clone();
    }
}

record StoredPublicBoardLink(UUID boardId, String title, BoardStatus status, int shareLinkVersion) {}
