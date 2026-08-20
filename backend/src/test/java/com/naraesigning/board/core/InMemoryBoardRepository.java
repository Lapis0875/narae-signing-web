package com.naraesigning.board.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class InMemoryBoardRepository implements BoardRepository {
    private static final Instant CREATED_AT = Instant.parse("2026-08-20T00:00:00Z");
    private final Map<UUID, StoredBoard> records = new LinkedHashMap<>();

    @Override
    public StoredBoard create(NewBoard board) {
        var stored = new StoredBoard(
                board.id(), board.owner(), board.title(), board.status(),
                board.canvasWidth(), board.canvasHeight(), board.share(), CREATED_AT, CREATED_AT);
        records.put(board.id(), stored);
        return stored;
    }

    @Override
    public List<StoredBoard> list(BoardOwner owner) {
        var result = new ArrayList<StoredBoard>();
        records.values().stream()
                .filter(board -> board.owner().equals(owner))
                .filter(board -> board.status() != BoardStatus.DELETING)
                .forEach(result::add);
        return List.copyOf(result);
    }

    @Override
    public Optional<StoredBoard> find(BoardOwner owner, UUID boardId) {
        return Optional.ofNullable(records.get(boardId))
                .filter(board -> board.owner().equals(owner))
                .filter(board -> board.status() != BoardStatus.DELETING);
    }

    @Override
    public Optional<StoredBoard> rename(BoardOwner owner, UUID boardId, BoardTitle title) {
        var current = find(owner, boardId);
        current.ifPresent(board -> records.put(boardId, board.withTitle(title)));
        return current.map(board -> board.withTitle(title));
    }

    @Override
    public Optional<StoredBoard> lockShare(BoardOwner owner, UUID boardId) {
        return find(owner, boardId);
    }

    @Override
    public Optional<StoredBoard> replaceShare(
            BoardOwner owner, UUID boardId, int expectedVersion, StoredShare share) {
        var current = find(owner, boardId)
                .filter(board -> board.share().version() == expectedVersion);
        current.ifPresent(board -> records.put(boardId, board.withShare(share)));
        return current.map(board -> board.withShare(share));
    }

    @Override
    public Optional<StoredPublicBoardLink> findPublicByLookupHash(byte[] lookupHash) {
        return records.values().stream()
                .filter(board -> board.status() != BoardStatus.DELETING)
                .filter(board -> Arrays.equals(board.share().lookupHash(), lookupHash))
                .findFirst()
                .map(board -> new StoredPublicBoardLink(
                        board.id(), board.title().value(), board.status(), board.share().version()));
    }

    StoredBoard stored(UUID boardId) {
        return records.get(boardId);
    }

    void markDeleting(UUID boardId) {
        records.computeIfPresent(boardId, (ignored, board) -> board.withStatus(BoardStatus.DELETING));
    }
}
