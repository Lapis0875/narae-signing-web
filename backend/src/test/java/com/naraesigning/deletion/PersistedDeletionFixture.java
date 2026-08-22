package com.naraesigning.deletion;

import com.naraesigning.background.BackgroundObjectStore;
import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.EncryptedValue;
import com.naraesigning.crypto.VersionedCryptoService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class PersistedDeletionFixture {
    private static final Instant START = Instant.parse("2026-08-22T00:00:00Z");
    private final MutableClock clock;
    private final PersistedStore store;
    private final FakeObjects objects;
    private final VersionedCryptoService crypto;

    private PersistedDeletionFixture(MutableClock clock, PersistedStore store,
            FakeObjects objects, VersionedCryptoService crypto) {
        this.clock = clock;
        this.store = store;
        this.objects = objects;
        this.crypto = crypto;
    }

    static PersistedDeletionFixture create(String... objectKeys) {
        var clock = new MutableClock(START);
        var crypto = new VersionedCryptoService(Map.of(1, new byte[32]), 1);
        var boardId = UUID.randomUUID();
        var rows = new LinkedHashMap<UUID, JobRow>();
        for (var objectKey : objectKeys) {
            var jobId = UUID.randomUUID();
            var encrypted = crypto.encrypt(objectKey.getBytes(StandardCharsets.UTF_8),
                    CryptoContext.field("board-deletion-job", jobId.toString(), "object-key"));
            rows.put(jobId, new JobRow(jobId, boardId, objectKey, encrypted));
        }
        return new PersistedDeletionFixture(clock, new PersistedStore(rows), new FakeObjects(), crypto);
    }

    MutableClock clock() { return clock; }
    PersistedStore store() { return store; }
    FakeObjects objects() { return objects; }
    BoardDeletionWorker worker() { return newWorker(); }
    BoardDeletionWorker newWorker() { return new BoardDeletionWorker(store, objects, crypto, clock); }

    static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant current) { this.current = current; }
        void advance(Duration duration) { current = current.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }

    static final class FakeObjects implements BackgroundObjectStore {
        private final Map<String, Integer> failures = new LinkedHashMap<>();
        private final Map<String, Runnable> beforeDelete = new LinkedHashMap<>();
        private final List<String> deleted = new ArrayList<>();

        void failNext(String key, int count) { failures.put(key, count); }
        void beforeNextDelete(String key, Runnable action) { beforeDelete.put(key, action); }
        int deletes(String key) { return Math.toIntExact(deleted.stream().filter(key::equals).count()); }
        @Override public byte[] get(String key) { throw new UnsupportedOperationException(); }
        @Override public void put(String key, byte[] bytes) { throw new UnsupportedOperationException(); }
        @Override public void delete(String key) {
            var action = beforeDelete.remove(key);
            if (action != null) action.run();
            deleted.add(key);
            var remaining = failures.getOrDefault(key, 0);
            if (remaining > 0) {
                failures.put(key, remaining - 1);
                throw new IllegalStateException("synthetic object-store outage");
            }
        }
    }

    static final class PersistedStore implements BoardDeletionStore {
        private final Map<UUID, JobRow> jobs;
        private final UUID boardId;
        private final Set<String> graphRows = new java.util.LinkedHashSet<>(
                List.of("board", "roster", "slot", "background"));

        PersistedStore(Map<UUID, JobRow> jobs) {
            this.jobs = jobs;
            this.boardId = jobs.values().iterator().next().boardId;
        }
        UUID boardId() { return boardId; }
        int graphRowCount() { return graphRows.size(); }
        int jobRowCount() { return jobs.size(); }
        Optional<String> boardStatusRow() {
            return graphRows.contains("board") ? Optional.of("DELETING") : Optional.empty();
        }
        String status(String key) { return row(key).status.name(); }
        int attemptCount(String key) { return row(key).attemptCount; }
        Instant nextAttemptAt(String key) { return row(key).nextAttemptAt; }
        Instant leaseExpiresAt(String key) { return row(key).leaseExpiresAt; }
        String lastError(String key) { return row(key).lastError; }

        void crashPollerAfterClaim(Instant now) {
            var claimed = claim(UUID.randomUUID(), now, BoardDeletionWorker.LEASE);
            if (claimed.isEmpty()) throw new IllegalStateException("expected persisted claim");
        }

        @Override public void begin(UUID ownerId, UUID boardId) {}

        @Override
        public List<DeletionJob> claim(UUID token, Instant now, Duration lease) {
            var claimed = new ArrayList<DeletionJob>();
            for (var row : jobs.values()) {
                var pending = row.status == Status.PENDING && !now.isBefore(row.nextAttemptAt);
                var expired = row.status == Status.PROCESSING && !now.isBefore(row.leaseExpiresAt);
                if (!pending && !expired) continue;
                row.status = Status.PROCESSING;
                row.attemptCount++;
                row.leaseToken = token;
                row.leaseExpiresAt = now.plus(lease);
                claimed.add(row.view());
            }
            return List.copyOf(claimed);
        }

        @Override
        public boolean renew(UUID id, UUID token, Instant now, Duration lease) {
            var row = jobs.get(id);
            if (row == null || row.status != Status.PROCESSING || !token.equals(row.leaseToken)
                    || !now.isBefore(row.leaseExpiresAt)) return false;
            row.leaseExpiresAt = now.plus(lease);
            return true;
        }

        @Override
        public void complete(UUID id, UUID token) {
            var row = jobs.get(id);
            if (row == null || row.status != Status.PROCESSING || !token.equals(row.leaseToken)) return;
            row.status = Status.COMPLETED;
            row.leaseToken = null;
            row.leaseExpiresAt = null;
            row.lastError = null;
        }

        @Override
        public void retry(UUID id, UUID token, Instant now, int attemptCount) {
            var row = jobs.get(id);
            if (row == null || row.status != Status.PROCESSING || !token.equals(row.leaseToken)) return;
            var exponent = Math.min(Math.max(attemptCount - 1, 0), 6);
            var delay = Duration.ofMinutes(Math.min(1L << exponent, 60));
            row.status = Status.PENDING;
            row.nextAttemptAt = now.plus(delay);
            row.leaseToken = null;
            row.leaseExpiresAt = null;
            row.lastError = "OBJECT_DELETE_FAILED";
        }

        @Override
        public void finalizeReadyBoards() {
            if (!jobs.isEmpty() && jobs.values().stream().allMatch(row -> row.status == Status.COMPLETED)) {
                graphRows.clear();
                jobs.clear();
            }
        }

        private JobRow row(String key) {
            return jobs.values().stream().filter(row -> row.objectKey.equals(key)).findFirst().orElseThrow();
        }
    }

    private enum Status { PENDING, PROCESSING, COMPLETED }

    private static final class JobRow {
        private final UUID id;
        private final UUID boardId;
        private final String objectKey;
        private final EncryptedValue encrypted;
        private Status status = Status.PENDING;
        private int attemptCount;
        private Instant nextAttemptAt = START;
        private UUID leaseToken;
        private Instant leaseExpiresAt;
        private String lastError;

        JobRow(UUID id, UUID boardId, String objectKey, EncryptedValue encrypted) {
            this.id = id;
            this.boardId = boardId;
            this.objectKey = objectKey;
            this.encrypted = encrypted;
        }

        DeletionJob view() {
            return new DeletionJob(id, boardId, encrypted, attemptCount, leaseToken, leaseExpiresAt);
        }
    }
}
