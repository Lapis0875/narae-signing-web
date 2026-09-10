package com.naraesigning.roster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class InMemoryRosterRepository implements RosterRepository {
    private final Map<UUID, StoredRosterEntry> records = new LinkedHashMap<>();
    private String boardStatus = "DRAFT";
    private boolean failAfterFirstReplacementMutation;
    private int replacementMutationCount;

    @Override
    public List<StoredRosterEntry> list(UUID boardId) {
        return records.values().stream().filter(entry -> entry.boardId().equals(boardId)).toList();
    }

    @Override
    public StoredRosterEntry create(UUID boardId, NewRosterEntry entry) {
        requireBoardEditable();
        if (list(boardId).size() >= RosterService.MAX_ROWS) throw new RosterInputException("ROW_LIMIT");
        requireUnique(entry.hmac(), null);
        var stored = stored(boardId, entry);
        records.put(stored.id(), stored);
        return stored;
    }

    @Override
    public StoredRosterEntry update(UUID boardId, UUID entryId, NewRosterEntry entry) {
        requireBoardEditable();
        var current = required(boardId, entryId);
        if (current.submitted()) throw new RosterUnavailableException();
        requireUnique(entry.hmac(), entryId);
        var updated = new StoredRosterEntry(entryId, boardId, entry.identity(), entry.hmac(), false, current.slot());
        records.put(entryId, updated);
        return updated;
    }

    @Override
    public void delete(UUID boardId, UUID entryId) {
        requireBoardEditable();
        if (required(boardId, entryId).submitted()) throw new RosterUnavailableException();
        records.remove(entryId);
    }

    @Override
    public List<StoredRosterEntry> replace(UUID boardId, List<NewRosterEntry> entries) {
        if (!"DRAFT".equals(boardStatus)) throw new RosterUnavailableException();
        if (failAfterFirstReplacementMutation && !records.isEmpty()) {
            records.remove(records.keySet().iterator().next());
            replacementMutationCount++;
            throw new IllegalStateException("synthetic persistence failure after first mutation");
        }
        var replacement = new LinkedHashMap<UUID, StoredRosterEntry>();
        for (var candidate : entries) {
            var retained = records.values().stream()
                    .filter(value -> Arrays.equals(value.hmac(), candidate.hmac()))
                    .findFirst().orElseGet(() -> stored(boardId, candidate));
            replacement.put(retained.id(), retained);
        }
        records.clear();
        records.putAll(replacement);
        return List.copyOf(records.values());
    }

    void open() { boardStatus = "OPEN"; }
    void markSubmitted(UUID entryId) {
        records.computeIfPresent(entryId, (ignored, value) -> new StoredRosterEntry(
                value.id(), value.boardId(), value.identity(), value.hmac(), true, value.slot()));
    }
    void resetSubmitted(UUID entryId) {
        records.computeIfPresent(entryId, (ignored, value) -> new StoredRosterEntry(
                value.id(), value.boardId(), value.identity(), value.hmac(), false, value.slot()));
    }
    void place(UUID entryId) {
        records.computeIfPresent(entryId, (ignored, value) -> new StoredRosterEntry(
                value.id(), value.boardId(), value.identity(), value.hmac(), value.submitted(),
                new StoredSlot(value.slot().id(), "PLACED", new java.math.BigDecimal("0.1"),
                        new java.math.BigDecimal("0.2"), new java.math.BigDecimal("0.3"),
                        new java.math.BigDecimal("0.4"), 7)));
    }
    void failAfterFirstReplacementMutation() { failAfterFirstReplacementMutation = true; }
    int replacementMutationCount() { return replacementMutationCount; }
    StoredRosterEntry storedEntry(UUID entryId) { return records.get(entryId); }
    Map<UUID, StoredRosterEntry> snapshot() { return new LinkedHashMap<>(records); }
    void restore(Map<UUID, StoredRosterEntry> snapshot) {
        records.clear();
        records.putAll(snapshot);
    }

    List<String> observableGraph(UUID boardId) {
        return list(boardId).stream().map(value -> value.id() + "|"
                + java.util.Base64.getEncoder().encodeToString(value.identity().ciphertext()) + "|"
                + java.util.Base64.getEncoder().encodeToString(value.identity().nonce()) + "|"
                + value.identity().keyVersion() + "|"
                + java.util.Base64.getEncoder().encodeToString(value.hmac()) + "|"
                + value.submitted() + "|" + value.slot()).toList();
    }

    private void requireBoardEditable() {
        if (!List.of("DRAFT", "OPEN").contains(boardStatus)) throw new RosterUnavailableException();
    }

    private StoredRosterEntry required(UUID boardId, UUID entryId) {
        var value = records.get(entryId);
        if (value == null || !value.boardId().equals(boardId)) throw new RosterUnavailableException();
        return value;
    }

    private void requireUnique(byte[] hmac, UUID except) {
        if (records.values().stream().anyMatch(value -> !value.id().equals(except)
                && Arrays.equals(value.hmac(), hmac))) throw new RosterInputException("DUPLICATE_IDENTITY");
    }

    private static StoredRosterEntry stored(UUID boardId, NewRosterEntry entry) {
        return new StoredRosterEntry(entry.id(), boardId, entry.identity(), entry.hmac(), false,
                new StoredSlot(entry.slotId(), "UNPLACED", null, null, null, null, 0));
    }
}
