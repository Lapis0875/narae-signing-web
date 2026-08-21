package com.naraesigning.signer.identify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class PublicIdentifyRateLimiter {
    static final int MAXIMUM_ENTRIES = 10_000;
    private static final Duration IDLE_EXPIRY = Duration.ofMinutes(30);
    private static final int PAIR_CAPACITY = 60;
    private static final double PAIR_REFILL_PER_SECOND = 1;
    private static final int IP_CAPACITY = 120;
    private static final double IP_REFILL_PER_SECOND = 2;
    private static final int MAXIMUM_RETRY_AFTER_SECONDS = 60;

    private final Clock clock;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>();

    PublicIdentifyRateLimiter(Clock clock) {
        this.clock = clock;
    }

    synchronized Admission admit(byte[] linkHash, String clientIp) {
        Instant now = clock.instant();
        expireIdle(now);
        var pair = bucket("pair:" + Base64.getEncoder().encodeToString(linkHash) + ':' + clientIp,
                PAIR_CAPACITY, now);
        var ip = bucket("ip:" + clientIp, IP_CAPACITY, now);
        pair.refill(now, PAIR_CAPACITY, PAIR_REFILL_PER_SECOND);
        ip.refill(now, IP_CAPACITY, IP_REFILL_PER_SECOND);
        pair.lastSeen = now;
        ip.lastSeen = now;
        if (pair.tokens >= 1 && ip.tokens >= 1) {
            pair.tokens -= 1;
            ip.tokens -= 1;
            return Admission.allowedRequest();
        }
        double pairWait = pair.tokens >= 1 ? 0 : (1 - pair.tokens) / PAIR_REFILL_PER_SECOND;
        double ipWait = ip.tokens >= 1 ? 0 : (1 - ip.tokens) / IP_REFILL_PER_SECOND;
        int retryAfter = (int) Math.ceil(Math.max(pairWait, ipWait));
        return Admission.denied(Math.clamp(retryAfter, 1, MAXIMUM_RETRY_AFTER_SECONDS));
    }

    Instant now() {
        return clock.instant();
    }

    synchronized int entryCount() {
        expireIdle(clock.instant());
        return buckets.size();
    }

    synchronized boolean containsPair(byte[] linkHash, String clientIp) {
        expireIdle(clock.instant());
        return buckets.containsKey("pair:" + Base64.getEncoder().encodeToString(linkHash) + ':' + clientIp);
    }

    private Bucket bucket(String key, int capacity, Instant now) {
        var existing = buckets.get(key);
        if (existing != null) {
            return existing;
        }
        if (buckets.size() >= MAXIMUM_ENTRIES) {
            evictOldestIdle();
        }
        var created = new Bucket(capacity, now);
        buckets.put(key, created);
        return created;
    }

    private void expireIdle(Instant now) {
        Instant cutoff = now.minus(IDLE_EXPIRY);
        buckets.values().removeIf(bucket -> !bucket.lastSeen.isAfter(cutoff));
    }

    private void evictOldestIdle() {
        Iterator<Map.Entry<String, Bucket>> entries = buckets.entrySet().iterator();
        Map.Entry<String, Bucket> oldest = null;
        while (entries.hasNext()) {
            var candidate = entries.next();
            if (oldest == null || candidate.getValue().lastSeen.isBefore(oldest.getValue().lastSeen)) {
                oldest = candidate;
            }
        }
        if (oldest != null) {
            buckets.remove(oldest.getKey());
        }
    }

    record Admission(boolean allowed, int retryAfterSeconds) {
        static Admission allowedRequest() {
            return new Admission(true, 0);
        }

        static Admission denied(int retryAfterSeconds) {
            return new Admission(false, retryAfterSeconds);
        }
    }

    private static final class Bucket {
        private double tokens;
        private Instant refilledAt;
        private Instant lastSeen;

        Bucket(int capacity, Instant now) {
            tokens = capacity;
            refilledAt = now;
            lastSeen = now;
        }

        void refill(Instant now, int capacity, double ratePerSecond) {
            double elapsedSeconds = Duration.between(refilledAt, now).toNanos() / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                tokens = Math.min(capacity, tokens + elapsedSeconds * ratePerSecond);
                refilledAt = now;
            }
        }
    }
}
