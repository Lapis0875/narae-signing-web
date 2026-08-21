package com.naraesigning.background;

import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.VersionedCryptoService;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

public final class BackgroundAssetService {
    private final BackgroundImageProcessor processor;
    private final BackgroundObjectStore objects;
    private final BackgroundAssetRepository repository;
    private final VersionedCryptoService crypto;
    private final TransactionOperations transactions;

    BackgroundAssetService(BackgroundImageProcessor processor, BackgroundObjectStore objects,
            BackgroundAssetRepository repository, VersionedCryptoService crypto,
            TransactionOperations transactions) {
        this.processor = processor;
        this.objects = objects;
        this.repository = repository;
        this.crypto = crypto;
        this.transactions = transactions;
    }

    public BackgroundAssetView replace(UUID boardId, byte[] input, String declaredMimeType,
            CanvasSize currentCanvas, CanvasChange change) {
        var normalized = processor.normalize(input, declaredMimeType, currentCanvas, change);
        var assetId = UUID.randomUUID();
        var objectKey = "background/" + boardId + "/" + assetId;
        var objectKeyBytes = objectKey.getBytes(StandardCharsets.UTF_8);
        var normalizedBytes = normalized.bytes();
        try {
            var stored = new StoredBackgroundAsset(assetId, boardId,
                    crypto.encrypt(objectKeyBytes,
                            CryptoContext.field("background-asset", assetId.toString(), "object-key")),
                    normalized.canvas().width(), normalized.canvas().height(), normalized.mimeType());
            try {
                objects.put(objectKey, BackgroundCipherEnvelope.encrypt(normalizedBytes, crypto, assetId));
            } catch (RuntimeException exception) {
                deleteOrSchedule(stored, objectKey, exception);
                throw exception;
            }
            try {
                transactions.executeWithoutResult(status -> replaceInTransaction(stored));
            } catch (RuntimeException exception) {
                deleteOrSchedule(stored, objectKey, exception);
                throw exception;
            }
            return new BackgroundAssetView(assetId, stored.displayWidth(), stored.displayHeight(), stored.mimeType());
        } finally {
            Arrays.fill(objectKeyBytes, (byte) 0);
            Arrays.fill(normalizedBytes, (byte) 0);
        }
    }

    public Optional<BackgroundContent> current(UUID boardId) {
        return repository.current(boardId).map(this::read);
    }

    private BackgroundContent read(StoredBackgroundAsset asset) {
        byte[] objectKey = null;
        byte[] envelope = null;
        byte[] plaintext = null;
        try {
            objectKey = crypto.decrypt(asset.encryptedObjectKey(),
                    CryptoContext.field("background-asset", asset.id().toString(), "object-key"));
            envelope = objects.get(new String(objectKey, StandardCharsets.UTF_8));
            plaintext = BackgroundCipherEnvelope.decrypt(envelope, crypto, asset.id());
            return new BackgroundContent(plaintext, asset.mimeType());
        } finally {
            if (objectKey != null) Arrays.fill(objectKey, (byte) 0);
            if (envelope != null) Arrays.fill(envelope, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private void replaceInTransaction(StoredBackgroundAsset replacement) {
        var previous = repository.current(replacement.boardId());
        repository.insertAndPoint(replacement);
        previous.ifPresent(asset -> repository.insertCleanup(cleanup(asset, CleanupReason.BACKGROUND_REPLACED)));
    }

    private void deleteOrSchedule(StoredBackgroundAsset asset, String objectKey, RuntimeException failure) {
        try {
            objects.delete(objectKey);
        } catch (RuntimeException deletionFailure) {
            try {
                transactions.executeWithoutResult(status ->
                        repository.insertCleanup(cleanup(asset, CleanupReason.ORPHAN_CLEANUP)));
            } catch (RuntimeException persistenceFailure) {
                deletionFailure.addSuppressed(persistenceFailure);
            }
            failure.addSuppressed(deletionFailure);
        }
    }

    private CleanupJob cleanup(StoredBackgroundAsset asset, CleanupReason reason) {
        var objectKey = crypto.decrypt(asset.encryptedObjectKey(),
                CryptoContext.field("background-asset", asset.id().toString(), "object-key"));
        try {
            var jobId = UUID.randomUUID();
            return new CleanupJob(jobId, asset.boardId(),
                    crypto.encrypt(objectKey,
                            CryptoContext.field("background-cleanup", jobId.toString(), "object-key")),
                    reason, CleanupStatus.PENDING, 0);
        } finally {
            Arrays.fill(objectKey, (byte) 0);
        }
    }
}
