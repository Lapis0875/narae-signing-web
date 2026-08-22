package com.naraesigning.deletion;

import org.springframework.scheduling.annotation.Scheduled;

final class BoardDeletionScheduler {
    private final BoardDeletionWorker worker;

    BoardDeletionScheduler(BoardDeletionWorker worker) { this.worker = worker; }

    @Scheduled(fixedDelayString = "${app.board-deletion-delay-ms:60000}")
    void run() { worker.runOnce(); }
}
