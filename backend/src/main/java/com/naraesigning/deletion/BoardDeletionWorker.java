package com.naraesigning.deletion;

import com.naraesigning.background.BackgroundObjectStore;
import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.VersionedCryptoService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

final class BoardDeletionWorker {
    static final Duration LEASE = Duration.ofMinutes(5);
    private final BoardDeletionStore store;
    private final BackgroundObjectStore objects;
    private final VersionedCryptoService crypto;
    private final Clock clock;

    BoardDeletionWorker(BoardDeletionStore store, BackgroundObjectStore objects,
            VersionedCryptoService crypto, Clock clock) {
        this.store = store;
        this.objects = objects;
        this.crypto = crypto;
        this.clock = clock;
    }

    void runOnce() {
        var token = UUID.randomUUID();
        for (var job : store.claim(token, clock.instant(), LEASE)) process(job);
        store.finalizeReadyBoards();
    }

    private void process(DeletionJob job) {
        var now = clock.instant();
        if (!store.renew(job.id(), job.leaseToken(), now, LEASE)) return;
        byte[] plaintext = null;
        try {
            plaintext = crypto.decrypt(job.encryptedObjectKey(),
                    CryptoContext.field("board-deletion-job", job.id().toString(), "object-key"));
            objects.delete(new String(plaintext, StandardCharsets.UTF_8));
            store.complete(job.id(), job.leaseToken());
        } catch (RuntimeException exception) {
            store.retry(job.id(), job.leaseToken(), clock.instant(), job.attemptCount());
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }
}
