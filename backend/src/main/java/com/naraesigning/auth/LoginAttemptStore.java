package com.naraesigning.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

class LoginAttemptStore {
    static final String IP_LOCK_SQL =
            "select pg_advisory_xact_lock(hashtextextended(?, 8465720))";
    static final String GLOBAL_LOCK_SQL = "select pg_advisory_xact_lock(8465722)";
    static final String EMAIL_LOCK_SQL = "select pg_advisory_xact_lock(hashtextextended("
            + "octet_length(canonical_email)::text || ':' || canonical_email "
            + "|| octet_length(trusted_client_ip)::text || ':' || trusted_client_ip, 8465721)) "
            + "from (values (cast(? as text), cast(? as text))) "
            + "lock_key(canonical_email, trusted_client_ip)";
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final Duration FAILURE_RETENTION = Duration.ofMinutes(15);
    private static final Duration LOCK = Duration.ofMinutes(15);
    private static final int IP_LIMIT = 20;
    private static final int EMAILS_PER_IP_LIMIT = 100;
    private static final int GLOBAL_IP_LIMIT = 10_000;
    private static final int CLEANUP_BATCH = 500;
    private final JdbcOperations jdbc;
    private final Clock clock;

    LoginAttemptStore(JdbcOperations jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = AuthApiException.class)
    UUID authenticate(String clientIp, String canonicalEmail, Supplier<UUID> verifier) {
        requireSafeLockPart(clientIp);
        requireSafeLockPart(canonicalEmail);
        Instant now = clock.instant();
        lockIp(clientIp);
        cleanupExpiredFailures(now);
        cleanupExpiredFailuresForIp(clientIp, now);
        admitIp(clientIp, now);
        lockEmail(clientIp, canonicalEmail);
        FailureState state = failureState(clientIp, canonicalEmail);
        if (state != null && state.lockedUntil() != null && now.isBefore(state.lockedUntil())) {
            throw AuthApiException.rateLimited();
        }

        UUID adminUserId = verifier.get();
        if (adminUserId != null) {
            jdbc.update(
                    "delete from login_failure_state where client_ip = cast(? as inet) and canonical_email = ?",
                    clientIp, canonicalEmail);
            return adminUserId;
        }

        int failures = state == null || state.lockedUntil() != null ? 1 : state.failures() + 1;
        Instant lockedUntil = failures >= 5 ? now.plus(LOCK) : null;
        if (state == null) {
            Integer keys = jdbc.queryForObject(
                    "select count(*) from login_failure_state where client_ip = cast(? as inet)",
                    Integer.class,
                    clientIp);
            if (keys != null && keys >= EMAILS_PER_IP_LIMIT) {
                throw AuthApiException.rateLimited();
            }
            jdbc.update(
                    "insert into login_failure_state "
                            + "(client_ip, canonical_email, consecutive_failures, locked_until, updated_at) "
                            + "values (cast(? as inet), ?, ?, ?, ?)",
                    clientIp, canonicalEmail, failures, timestamp(lockedUntil), timestamp(now));
        } else {
            jdbc.update(
                    "update login_failure_state set consecutive_failures = ?, locked_until = ?, updated_at = ? "
                            + "where client_ip = cast(? as inet) and canonical_email = ?",
                    failures, timestamp(lockedUntil), timestamp(now), clientIp, canonicalEmail);
        }
        throw AuthApiException.authenticationFailed();
    }

    @Scheduled(fixedDelayString = "${app.login-defense-cleanup-delay-ms:60000}")
    @Transactional
    void cleanupExpiredFailures() {
        cleanupExpiredFailures(clock.instant());
    }

    private void admitIp(String clientIp, Instant now) {
        IpWindow window = ipWindow(clientIp);
        if (window != null && now.isBefore(window.startedAt().plus(WINDOW))) {
            if (window.attempts() >= IP_LIMIT) {
                throw AuthApiException.rateLimited();
            }
            jdbc.update(
                    "update admin_login_ip_window set attempt_count = attempt_count + 1 "
                            + "where trusted_client_ip = cast(? as inet)",
                    clientIp);
            return;
        }

        lockGlobal();
        jdbc.update(
                "delete from admin_login_ip_window where window_started_at <= ?",
                timestamp(now.minus(WINDOW)));
        Integer ips = jdbc.queryForObject("select count(*) from admin_login_ip_window", Integer.class);
        if (ips != null && ips >= GLOBAL_IP_LIMIT) {
            throw AuthApiException.rateLimited();
        }
        jdbc.update(
                "insert into admin_login_ip_window (trusted_client_ip, window_started_at, attempt_count) "
                        + "values (cast(? as inet), ?, 1)",
                clientIp, timestamp(now));
    }

    private IpWindow ipWindow(String clientIp) {
        return jdbc.query(
                "select window_started_at, attempt_count from admin_login_ip_window "
                        + "where trusted_client_ip = cast(? as inet)",
                resultSet -> resultSet.next()
                        ? new IpWindow(resultSet.getTimestamp(1).toInstant(), resultSet.getInt(2))
                        : null,
                clientIp);
    }

    private void cleanupExpiredFailures(Instant now) {
        Instant cutoff = now.minus(FAILURE_RETENTION);
        jdbc.update(
                "delete from login_failure_state where ctid in ("
                        + "select ctid from login_failure_state where "
                        + "(locked_until is null and updated_at <= ?) "
                        + "or (locked_until is not null and locked_until <= ?) "
                        + "order by updated_at limit " + CLEANUP_BATCH + ")",
                timestamp(cutoff), timestamp(cutoff));
    }

    private void cleanupExpiredFailuresForIp(String clientIp, Instant now) {
        Instant cutoff = now.minus(FAILURE_RETENTION);
        jdbc.update(
                "delete from login_failure_state where client_ip = cast(? as inet) and "
                        + "((locked_until is null and updated_at <= ?) "
                        + "or (locked_until is not null and locked_until <= ?))",
                clientIp, timestamp(cutoff), timestamp(cutoff));
    }

    private FailureState failureState(String clientIp, String canonicalEmail) {
        return jdbc.query(
                "select consecutive_failures, locked_until from login_failure_state "
                        + "where client_ip = cast(? as inet) and canonical_email = ?",
                resultSet -> resultSet.next()
                        ? new FailureState(
                                resultSet.getInt(1),
                                resultSet.getTimestamp(2) == null ? null : resultSet.getTimestamp(2).toInstant())
                        : null,
                clientIp, canonicalEmail);
    }

    private void lockIp(String clientIp) {
        jdbc.query(IP_LOCK_SQL, resultSet -> null, clientIp);
    }

    private void lockEmail(String clientIp, String canonicalEmail) {
        jdbc.query(EMAIL_LOCK_SQL, resultSet -> null, canonicalEmail, clientIp);
    }

    private void lockGlobal() {
        jdbc.query(GLOBAL_LOCK_SQL, resultSet -> null);
    }

    private static void requireSafeLockPart(String value) {
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Advisory lock key contains NUL");
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private record IpWindow(Instant startedAt, int attempts) {}

    private record FailureState(int failures, Instant lockedUntil) {}
}
