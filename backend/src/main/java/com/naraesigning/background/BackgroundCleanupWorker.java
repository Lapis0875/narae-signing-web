package com.naraesigning.background;

import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.VersionedCryptoService;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class BackgroundCleanupWorker {
    private final BackgroundAssetRepository repository;
    private final BackgroundObjectStore objects;
    private final VersionedCryptoService crypto;

    BackgroundCleanupWorker(BackgroundAssetRepository repository, BackgroundObjectStore objects,
            VersionedCryptoService crypto) {
        this.repository = repository;
        this.objects = objects;
        this.crypto = crypto;
    }

    public void runOnce() {
        for (var job : repository.claimCleanupJobs()) process(job);
    }

    private void process(CleanupJob job) {
        var keyBytes = crypto.decrypt(job.encryptedObjectKey(),
                CryptoContext.field("background-cleanup", job.id().toString(), "object-key"));
        try {
            var objectKey = new String(keyBytes, StandardCharsets.UTF_8);
            if (isCurrent(job, objectKey)) {
                repository.completeCleanup(job.id());
                return;
            }
            try {
                objects.delete(objectKey);
                repository.completeCleanup(job.id());
            } catch (BackgroundStoreException exception) {
                repository.retryCleanup(job.id());
            }
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    private boolean isCurrent(CleanupJob job, String cleanupKey) {
        return repository.current(job.boardId()).map(asset -> {
            var currentKey = crypto.decrypt(asset.encryptedObjectKey(),
                    CryptoContext.field("background-asset", asset.id().toString(), "object-key"));
            try {
                return cleanupKey.equals(new String(currentKey, StandardCharsets.UTF_8));
            } finally {
                Arrays.fill(currentKey, (byte) 0);
            }
        }).orElse(false);
    }
}
