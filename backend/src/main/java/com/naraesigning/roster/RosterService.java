package com.naraesigning.roster;

import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.VersionedCryptoService;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

public final class RosterService {
    public static final int MAX_ROWS = 50;
    private final RosterRepository repository;
    private final VersionedCryptoService crypto;
    private final TransactionOperations transactions;

    RosterService(RosterRepository repository, VersionedCryptoService crypto, TransactionOperations transactions) {
        this.repository = repository;
        this.crypto = crypto;
        this.transactions = transactions;
    }

    public List<RosterEntry> list(UUID boardId) {
        return repository.list(boardId).stream().map(this::view).toList();
    }

    public RosterEntry create(UUID boardId, RosterIdentity identity) {
        return transactions.execute(status -> view(repository.create(boardId, fresh(boardId, identity))));
    }

    public RosterEntry update(UUID boardId, UUID entryId, RosterIdentity identity) {
        return transactions.execute(status -> view(repository.update(
                boardId, entryId, encrypted(boardId, entryId, UUID.randomUUID(), identity))));
    }

    public void delete(UUID boardId, UUID entryId) {
        transactions.executeWithoutResult(status -> repository.delete(boardId, entryId));
    }

    public List<RosterEntry> replace(UUID boardId, List<RosterIdentity> identities) {
        validate(identities);
        return transactions.execute(status -> repository.replace(
                boardId, identities.stream().map(identity -> fresh(boardId, identity)).toList()))
                .stream().map(this::view).toList();
    }

    private NewRosterEntry fresh(UUID boardId, RosterIdentity identity) {
        return encrypted(boardId, UUID.randomUUID(), UUID.randomUUID(), identity);
    }

    private NewRosterEntry encrypted(UUID boardId, UUID entryId, UUID slotId, RosterIdentity identity) {
        var plaintext = identity.encode();
        try {
            return new NewRosterEntry(entryId, slotId,
                    crypto.encrypt(plaintext, CryptoContext.field("roster-entry", entryId.toString(), "identity")),
                    crypto.identityHmac(boardId.toString(), identity.organization(), identity.job(), identity.name()));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private RosterEntry view(StoredRosterEntry stored) {
        var plaintext = crypto.decrypt(stored.identity(),
                CryptoContext.field("roster-entry", stored.id().toString(), "identity"));
        try {
            var slot = stored.slot();
            return new RosterEntry(stored.id(), RosterIdentity.decode(plaintext), stored.submitted(),
                    new RosterEntry.Slot(slot.id(), slot.placementStatus(), slot.x(), slot.y(),
                            slot.width(), slot.height(), slot.revision()));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private void validate(List<RosterIdentity> identities) {
        if (identities == null || identities.size() > MAX_ROWS) throw new RosterInputException("ROW_LIMIT");
        var seen = new HashSet<String>();
        var errors = new java.util.ArrayList<RosterValidationError>();
        for (int index = 0; index < identities.size(); index++) {
            var identity = identities.get(index);
            var hmac = crypto.identityHmac("validation", identity.organization(), identity.job(), identity.name());
            if (!seen.add(java.util.Base64.getEncoder().encodeToString(hmac))) {
                errors.add(new RosterValidationError(index + 1, "DUPLICATE_IDENTITY"));
            }
        }
        if (!errors.isEmpty()) throw new RosterInputException(errors);
    }
}
