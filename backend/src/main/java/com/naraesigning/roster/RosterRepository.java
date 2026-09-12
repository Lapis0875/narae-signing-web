package com.naraesigning.roster;

import com.naraesigning.crypto.EncryptedValue;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

interface RosterRepository {
    List<StoredRosterEntry> list(UUID boardId);
    StoredRosterEntry create(UUID boardId, NewRosterEntry entry);
    StoredRosterEntry update(UUID boardId, UUID entryId, NewRosterEntry entry);
    void delete(UUID boardId, UUID entryId);
    List<StoredRosterEntry> replace(UUID boardId, List<NewRosterEntry> entries);
}

record NewRosterEntry(UUID id, UUID slotId, EncryptedValue identity, byte[] hmac) {
    NewRosterEntry { hmac = hmac.clone(); }
    @Override public byte[] hmac() { return hmac.clone(); }
}

record StoredRosterEntry(
        UUID id,
        UUID boardId,
        EncryptedValue identity,
        byte[] hmac,
        boolean submitted,
        StoredSlot slot) {
    StoredRosterEntry { hmac = hmac.clone(); }
    @Override public byte[] hmac() { return hmac.clone(); }
}

record StoredSlot(
        UUID id,
        String placementStatus,
        BigDecimal x,
        BigDecimal y,
        BigDecimal width,
        BigDecimal height,
        long revision) {}
