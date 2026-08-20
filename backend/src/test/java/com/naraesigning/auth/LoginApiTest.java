package com.naraesigning.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.naraesigning.NaraeSigningApplication;
import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = {NaraeSigningApplication.class, LoginApiTest.ClockConfiguration.class})
@AutoConfigureMockMvc
@Testcontainers
class LoginApiTest {
    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void clearDatabaseAndClock() {
        jdbc.update("delete from spring_session_attributes");
        jdbc.update("delete from spring_session");
        jdbc.update("delete from login_failure_state");
        jdbc.update("delete from admin_login_ip_window");
        jdbc.update("delete from admin_user");
        clock.set(Instant.parse("2026-08-20T00:00:00Z"));
    }

    @Test
    void loginSessionCsrfAndLogoutWorkThroughRealHttpAndPostgres() throws Exception {
        // Given: active administrator, browser CSRF cookie, and PostgreSQL notification listener.
        UUID adminUserId = UUID.randomUUID();
        jdbc.update(
                "insert into admin_user (id, email, password_hash, status) values (?, ?, ?, 'ACTIVE')",
                adminUserId,
                "operator@example.com",
                new BCryptPasswordEncoder(12).encode("correct-password-phrase"));
        Cookie csrf = mvc.perform(get("/api/v1/auth/csrf").secure(true))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");

        try (var listener = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            listener.createStatement().execute("listen narae_admin_session_invalidated");

            // When: canonical-email login crosses CSRF, trusted-IP, MVC, BCrypt, JDBC, and session boundaries.
            var login = mvc.perform(post("/api/v1/auth/login")
                            .secure(true)
                            .header("X-Narae-Client-IP", "2001:db8::1")
                            .header("X-XSRF-TOKEN", csrf.getValue())
                            .cookie(csrf)
                            .contentType(APPLICATION_JSON)
                            .content("{\"email\":\"Operator@EXAMPLE.COM\",\"password\":\"correct-password-phrase\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store, private"))
                    .andExpect(jsonPath("$.authenticated").value(true))
                    .andExpect(jsonPath("$.expiresAt").value("2026-08-20T12:00:00Z"))
                    .andExpect(cookie().secure("ADMIN_SESSION", true))
                    .andExpect(cookie().httpOnly("ADMIN_SESSION", true))
                    .andReturn().getResponse();
            Cookie adminSession = login.getCookie("ADMIN_SESSION");
            Cookie rotatedCsrf = login.getCookie("XSRF-TOKEN");

            // Then: session is current; logout deletes only admin state/cookies and publishes after transaction.
            mvc.perform(get("/api/v1/auth/session").secure(true).cookie(adminSession))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store, private"))
                    .andExpect(jsonPath("$.authenticated").value(true))
                    .andExpect(jsonPath("$.expiresAt").value("2026-08-20T12:00:00Z"));
            mvc.perform(post("/api/v1/auth/logout")
                            .secure(true)
                            .header("X-XSRF-TOKEN", rotatedCsrf.getValue())
                            .cookie(adminSession, rotatedCsrf, new Cookie("SIGNER_SESSION", "must-survive")))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string("Cache-Control", "no-store, private"))
                    .andExpect(cookie().maxAge("ADMIN_SESSION", 0))
                    .andExpect(cookie().maxAge("XSRF-TOKEN", 0))
                    .andExpect(cookie().doesNotExist("SIGNER_SESSION"));
            listener.createStatement().execute("select 1");
            assertThat(jdbc.queryForObject("select count(*) from spring_session", Integer.class)).isZero();
            assertThat(notificationPayloads(listener))
                    .singleElement()
                    .asString()
                    .contains(adminUserId.toString(), "LOGOUT");
        }
    }

    @Test
    void failuresPersistAcrossStoreRestartAndConcurrentFifthFailureLocksOnce() throws Exception {
        // Given: four committed failures in PostgreSQL and a newly constructed store.
        String ip = "198.51.100.10";
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(runAttempt(store(), ip, "admin@example.com")).extracting(AuthApiException::code)
                    .isEqualTo("AUTHENTICATION_FAILED");
        }
        var restartedStore = store();
        var barrier = new CyclicBarrier(2);

        // When: two transactions race on the same fifth-failure key.
        List<AuthApiException> outcomes;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await();
                return runAttempt(restartedStore, ip, "admin@example.com");
            });
            var second = executor.submit(() -> {
                barrier.await();
                return runAttempt(restartedStore, ip, "admin@example.com");
            });
            outcomes = List.of(first.get(), second.get());
        }

        // Then: advisory locks serialize updates; one failure commits and one sees persisted lock.
        assertThat(outcomes).extracting(AuthApiException::code)
                .containsExactlyInAnyOrder("AUTHENTICATION_FAILED", "LOGIN_RATE_LIMITED");
        assertThat(jdbc.queryForObject(
                        "select consecutive_failures from login_failure_state "
                                + "where client_ip = cast(? as inet) and canonical_email = ?",
                        Integer.class, ip, "admin@example.com"))
                .isEqualTo(5);
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(runAttempt(store(), ip, "admin@example.com")).extracting(AuthApiException::code)
                .isEqualTo("AUTHENTICATION_FAILED");
        assertThat(jdbc.queryForObject(
                        "select consecutive_failures from login_failure_state "
                                + "where client_ip = cast(? as inet) and canonical_email = ?",
                        Integer.class, ip, "admin@example.com"))
                .isOne();
    }

    @Test
    void ipEmailAndGlobalCapsReturnGeneric429WithoutOverflowRows() {
        // Given/When: one IP reaches 20 attempts in a persisted 60-second window.
        String ip = "198.51.100.20";
        for (int attempt = 0; attempt < 20; attempt++) {
            runAttempt(store(), ip, "window@example.com");
        }

        // Then: attempt 21 is rejected and counter cannot overflow its row constraint.
        assertThat(runAttempt(store(), ip, "window@example.com")).extracting(AuthApiException::code)
                .isEqualTo("LOGIN_RATE_LIMITED");
        assertThat(jdbc.queryForObject(
                "select attempt_count from admin_login_ip_window where trusted_client_ip = cast(? as inet)",
                Integer.class, ip)).isEqualTo(20);

        // Given/When: 100 distinct email keys persist for another IP across clock windows.
        String keyedIp = "198.51.100.21";
        for (int key = 0; key < 100; key++) {
            if (key > 0 && key % 20 == 0) {
                clock.advance(Duration.ofSeconds(61));
            }
            runAttempt(store(), keyedIp, "user" + key + "@example.com");
        }
        clock.advance(Duration.ofSeconds(61));

        // Then: key 101 is a generic rejection and creates no overflow state row.
        assertThat(runAttempt(store(), keyedIp, "overflow@example.com"))
                .extracting(AuthApiException::code).isEqualTo("LOGIN_RATE_LIMITED");
        assertThat(jdbc.queryForObject(
                "select count(*) from login_failure_state where client_ip = cast(? as inet)",
                Integer.class, keyedIp)).isEqualTo(100);

        // Given/When: global persisted IP-window table is at its 10,000-row cap.
        jdbc.update("delete from admin_login_ip_window");
        jdbc.update("insert into admin_login_ip_window (trusted_client_ip, window_started_at, attempt_count) "
                + "select ('10.' || ((g / 65536) % 256) || '.' || ((g / 256) % 256) || '.' || (g % 256))::inet, ?, 1 "
                + "from generate_series(0, 9999) g", Timestamp.from(clock.instant()));
        assertThat(runAttempt(store(), "203.0.113.250", "new@example.com"))
                .extracting(AuthApiException::code).isEqualTo("LOGIN_RATE_LIMITED");

        // Then: no global overflow row exists.
        assertThat(jdbc.queryForObject("select count(*) from admin_login_ip_window", Integer.class)).isEqualTo(10_000);
        assertThat(jdbc.queryForObject(
                "select count(*) from admin_login_ip_window where trusted_client_ip = cast(? as inet)",
                Integer.class, "203.0.113.250")).isZero();
    }

    @Test
    void rejectsDirectOrMalformedTrustedClientHeadersAndKeepsFailureGeneric() throws Exception {
        // Given: browser CSRF token.
        Cookie csrf = mvc.perform(get("/api/v1/auth/csrf").secure(true))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        var login = post("/api/v1/auth/login")
                .secure(true)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .cookie(csrf)
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"missing@example.com\",\"password\":\"unknown-password-phrase\"}");

        // When/Then: direct peer cannot self-assert trusted header; XFF never substitutes for it.
        mvc.perform(login.header("X-Narae-Client-IP", "198.51.100.30")
                        .with(request -> { request.setRemoteAddr("203.0.113.1"); return request; }))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .header("X-Forwarded-For", "198.51.100.30")
                        .cookie(csrf)
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"missing@example.com\",\"password\":\"unknown-password-phrase\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store, private"));
        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .header("X-Narae-Client-IP", "198.51.100.30, 198.51.100.31")
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .cookie(csrf)
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"missing@example.com\",\"password\":\"unknown-password-phrase\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .header("X-Narae-Client-IP", "198.51.100.30")
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .cookie(csrf)
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"missing@example.com\",\"password\":\"unknown-password-phrase\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호를 확인해 주세요."));
    }

    @Test
    void activeIpWindowDoesNotAcquireGlobalLock() throws Exception {
        // Given: active IP window and another transaction holding only the global cap lock.
        String ip = "198.51.100.40";
        jdbc.update(
                "insert into admin_login_ip_window (trusted_client_ip, window_started_at, attempt_count) "
                        + "values (cast(? as inet), ?, 1)",
                ip, Timestamp.from(clock.instant()));
        try (var globalLocker = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            globalLocker.setAutoCommit(false);
            globalLocker.createStatement().execute(LoginAttemptStore.GLOBAL_LOCK_SQL);

            // When: login transaction enters with no need to create or replace an IP window.
            AuthApiException outcome;
            try (var executor = Executors.newSingleThreadExecutor()) {
                outcome = executor.submit(() -> runAttempt(store(), ip, "active@example.com"))
                        .get(2, TimeUnit.SECONDS);
            }

            // Then: request completes without waiting for global lock.
            assertThat(outcome.code()).isEqualTo("AUTHENTICATION_FAILED");
            globalLocker.rollback();
        }
    }

    @Test
    void missingIpWaitsForIpLockBeforeGlobalLock() throws Exception {
        // Given: another transaction holds target per-IP lock.
        String ip = "198.51.100.41";
        try (var ipLocker = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var globalProbe = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            ipLocker.setAutoCommit(false);
            try (var statement = ipLocker.prepareStatement(LoginAttemptStore.IP_LOCK_SQL)) {
                statement.setString(1, ip);
                statement.execute();
            }
            var entered = new CountDownLatch(1);

            // When: missing-IP login starts and blocks on first advisory lock.
            try (var executor = Executors.newSingleThreadExecutor()) {
                var attempt = executor.submit(() -> {
                    entered.countDown();
                    return runAttempt(store(), ip, "ordered@example.com");
                });
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> attempt.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);

                // Then: global lock remains available until per-IP lock is released.
                globalProbe.setAutoCommit(false);
                try (var result = globalProbe.createStatement()
                        .executeQuery("select pg_try_advisory_xact_lock(8465722)")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                }
                globalProbe.rollback();
                ipLocker.commit();
                assertThat(attempt.get(2, TimeUnit.SECONDS).code()).isEqualTo("AUTHENTICATION_FAILED");
            }
        }
    }

    @Test
    void ordinaryFailuresExpireAcrossRestartAndScheduledCleanupIsBounded() {
        // Given: full 100-key cap contains ordinary failures from 15 minutes ago.
        String ip = "198.51.100.42";
        jdbc.update(
                "insert into login_failure_state "
                        + "(client_ip, canonical_email, consecutive_failures, locked_until, updated_at) "
                        + "select cast(? as inet), 'old' || g || '@example.com', 1, null, ? "
                        + "from generate_series(1, 100) g",
                ip, Timestamp.from(clock.instant()));
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        // When: newly constructed store handles next login transaction.
        assertThat(runAttempt(store(), ip, "fresh@example.com").code())
                .isEqualTo("AUTHENTICATION_FAILED");

        // Then: expired keys do not consume cap; only fresh failure remains.
        assertThat(jdbc.queryForObject(
                "select count(*) from login_failure_state where client_ip = cast(? as inet)",
                Integer.class, ip)).isOne();

        // Given: scheduled cleanup has 501 expired ordinary rows.
        jdbc.update("delete from login_failure_state");
        jdbc.update(
                "insert into login_failure_state "
                        + "(client_ip, canonical_email, consecutive_failures, locked_until, updated_at) "
                        + "select cast('198.51.100.43' as inet), 'expired' || g || '@example.com', 1, null, ? "
                        + "from generate_series(1, 501) g",
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(16))));

        // When/Then: one scheduled invocation removes at most its 500-row batch.
        new TransactionTemplate(transactions).executeWithoutResult(status -> store().cleanupExpiredFailures());
        assertThat(jdbc.queryForObject("select count(*) from login_failure_state", Integer.class)).isOne();

        // Given/When/Then: locked rows live until 15 minutes after locked_until.
        jdbc.update("delete from login_failure_state");
        jdbc.update(
                "insert into login_failure_state "
                        + "(client_ip, canonical_email, consecutive_failures, locked_until, updated_at) values "
                        + "(cast('198.51.100.44' as inet), 'expired-lock@example.com', 5, ?, ?), "
                        + "(cast('198.51.100.44' as inet), 'retained-lock@example.com', 5, ?, ?)",
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(15).plusSeconds(1))),
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(30))),
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(14).plusSeconds(59))),
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(30))));
        new TransactionTemplate(transactions).executeWithoutResult(status -> store().cleanupExpiredFailures());
        assertThat(jdbc.queryForList("select canonical_email from login_failure_state", String.class))
                .containsExactly("retained-lock@example.com");
    }

    @Test
    void advisoryLockSqlUsesApprovedSeedsAndDatabaseLengthPrefix() {
        assertThat(LoginAttemptStore.IP_LOCK_SQL)
                .isEqualTo("select pg_advisory_xact_lock(hashtextextended(?, 8465720))");
        assertThat(LoginAttemptStore.GLOBAL_LOCK_SQL)
                .isEqualTo("select pg_advisory_xact_lock(8465722)");
        assertThat(LoginAttemptStore.EMAIL_LOCK_SQL).contains(
                "octet_length(canonical_email)::text || ':' || canonical_email "
                        + "|| octet_length(trusted_client_ip)::text || ':' || trusted_client_ip",
                "8465721").doesNotContain("hashtextextended(?, 0)");
        assertThatThrownBy(() -> store().authenticate("198.51.100.45", "bad\0@example.com", () -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private LoginAttemptStore store() {
        return new LoginAttemptStore(jdbc, clock);
    }

    private AuthApiException runAttempt(LoginAttemptStore store, String ip, String email) {
        return new TransactionTemplate(transactions).execute(status -> {
            try {
                store.authenticate(ip, email, () -> null);
                throw new AssertionError("Expected rejected login");
            } catch (AuthApiException exception) {
                return exception;
            }
        });
    }

    private static List<String> notificationPayloads(java.sql.Connection connection) throws Exception {
        Object[] notifications = (Object[]) Class.forName("org.postgresql.PGConnection")
                .getMethod("getNotifications")
                .invoke(connection.unwrap(Class.forName("org.postgresql.PGConnection")));
        if (notifications == null) return List.of();
        return java.util.stream.Stream.of(notifications).map(notification -> {
            try {
                return (String) notification.getClass().getMethod("getParameter").invoke(notification);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }).toList();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    static final class MutableClock extends Clock {
        private Instant instant = Instant.EPOCH;

        synchronized void set(Instant value) { instant = value; }
        synchronized void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public synchronized Instant instant() { return instant; }
    }
}
