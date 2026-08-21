package com.naraesigning.signer.identify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PublicIdentifyRateLimiterTest {
    @Test
    void rejectsSixtyFirstPairRequestUntilOneTokenRefills() {
        // Given
        var clock = new MutableClock(Instant.parse("2026-08-21T00:00:00Z"));
        var limiter = new PublicIdentifyRateLimiter(clock);
        var linkHash = new byte[] {1, 2, 3};
        for (int attempt = 0; attempt < 60; attempt++) {
            assertThat(limiter.admit(linkHash, "198.51.100.10").allowed()).isTrue();
        }

        // When
        var denied = limiter.admit(linkHash, "198.51.100.10");

        // Then
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(1);
        clock.advanceSeconds(1);
        assertThat(limiter.admit(linkHash, "198.51.100.10").allowed()).isTrue();
    }

    @Test
    void rejectsOneHundredTwentyFirstIpRequestAcrossDistinctLinks() {
        // Given
        var clock = new MutableClock(Instant.parse("2026-08-21T00:00:00Z"));
        var limiter = new PublicIdentifyRateLimiter(clock);
        IntStream.range(0, 120).forEach(attempt -> assertThat(limiter.admit(
                new byte[] {(byte) attempt}, "198.51.100.20").allowed()).isTrue());

        // When
        var denied = limiter.admit(new byte[] {(byte) 121}, "198.51.100.20");

        // Then
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1, 60);
        clock.advanceMillis(500);
        assertThat(limiter.admit(new byte[] {(byte) 122}, "198.51.100.20").allowed()).isTrue();
    }

    @Test
    void expiresIdleEntriesAfterThirtyMinutes() {
        // Given
        var clock = new MutableClock(Instant.parse("2026-08-21T00:00:00Z"));
        var limiter = new PublicIdentifyRateLimiter(clock);
        limiter.admit(new byte[] {4}, "198.51.100.30");

        // When
        clock.advanceSeconds(30 * 60);

        // Then
        assertThat(limiter.entryCount()).isZero();
    }

    @Test
    void evictsOldestIdleEntriesAtTenThousandEntryCap() {
        // Given
        var clock = new MutableClock(Instant.parse("2026-08-21T00:00:00Z"));
        var limiter = new PublicIdentifyRateLimiter(clock);
        for (int value = 0; value < 5_000; value++) {
            limiter.admit(intBytes(value), "198.51." + value / 256 + '.' + value % 256);
        }
        assertThat(limiter.entryCount()).isEqualTo(PublicIdentifyRateLimiter.MAXIMUM_ENTRIES);
        clock.advanceSeconds(1);
        limiter.admit(intBytes(0), "198.51.0.0");

        // When
        limiter.admit(intBytes(5_001), "203.0.113.1");

        // Then
        assertThat(limiter.entryCount()).isEqualTo(PublicIdentifyRateLimiter.MAXIMUM_ENTRIES);
        assertThat(limiter.containsPair(intBytes(0), "198.51.0.0")).isTrue();
        assertThat(limiter.containsPair(intBytes(1), "198.51.0.1")).isFalse();
        assertThat(limiter.containsPair(intBytes(5_001), "203.0.113.1")).isTrue();
    }

    @Test
    void admitsDeterministicTwentyFiveSignerNatBurst() {
        // Given
        var limiter = new PublicIdentifyRateLimiter(
                new MutableClock(Instant.parse("2026-08-21T00:00:00Z")));

        // When
        var admissions = IntStream.range(0, 25)
                .mapToObj(attempt -> limiter.admit(new byte[] {9}, "192.0.2.10"))
                .toList();

        // Then
        assertThat(admissions).allMatch(PublicIdentifyRateLimiter.Admission::allowed);
    }

    private static byte[] intBytes(int value) {
        return java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
