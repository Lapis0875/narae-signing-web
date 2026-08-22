package com.naraesigning.deletion;

import com.naraesigning.crypto.EncryptedValue;
import java.time.Instant;
import java.util.UUID;

record DeletionJob(
        UUID id,
        UUID boardId,
        EncryptedValue encryptedObjectKey,
        int attemptCount,
        UUID leaseToken,
        Instant leaseExpiresAt) {}
