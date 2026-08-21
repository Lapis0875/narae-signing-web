package com.naraesigning.background;

import org.springframework.scheduling.annotation.Scheduled;

final class BackgroundCleanupScheduler {
    private final BackgroundCleanupWorker worker;

    BackgroundCleanupScheduler(BackgroundCleanupWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.background-cleanup-delay-ms:60000}")
    void run() {
        worker.runOnce();
    }
}
