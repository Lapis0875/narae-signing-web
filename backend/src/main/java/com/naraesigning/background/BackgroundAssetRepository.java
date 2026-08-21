package com.naraesigning.background;

import com.naraesigning.crypto.EncryptedValue;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface BackgroundAssetRepository {
    Optional<StoredBackgroundAsset> current(UUID boardId);
    void insertAndPoint(StoredBackgroundAsset asset);
    void insertCleanup(CleanupJob job);
    List<CleanupJob> claimCleanupJobs();
    void retryCleanup(UUID id);
    void completeCleanup(UUID id);
}

record StoredBackgroundAsset(
        UUID id,
        UUID boardId,
        EncryptedValue encryptedObjectKey,
        int displayWidth,
        int displayHeight,
        String mimeType) {}

record CleanupJob(
        UUID id,
        UUID boardId,
        EncryptedValue encryptedObjectKey,
        CleanupReason reason,
        CleanupStatus status,
        int attemptCount) {}

enum CleanupReason { BACKGROUND_REPLACED, ORPHAN_CLEANUP }
enum CleanupStatus { PENDING, PROCESSING, COMPLETED }
