package com.naraesigning.deletion;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface BoardDeletionStore {
    boolean begin(UUID ownerId, UUID boardId);
    List<DeletionJob> claim(UUID leaseToken, Instant now, Duration leaseDuration);
    boolean renew(UUID jobId, UUID leaseToken, Instant now, Duration leaseDuration);
    void complete(UUID jobId, UUID leaseToken);
    void retry(UUID jobId, UUID leaseToken, Instant now, int attemptCount);
    void finalizeReadyBoards();
}
