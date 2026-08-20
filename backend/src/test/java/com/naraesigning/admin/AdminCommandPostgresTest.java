package com.naraesigning.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.session.AdminSessionInvalidationPublisher;
import com.naraesigning.session.AdminSessionInvalidator;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class AdminCommandPostgresTest {
    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;

    @BeforeEach
    void migrateAndClearDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource());
        jdbc.update("delete from spring_session_attributes");
        jdbc.update("delete from spring_session");
        jdbc.update("delete from admin_user");
    }

    @Test
    void createAndResetUseConsoleHashCanonicallyAndInvalidateOnlyTargetSessions() throws Exception {
        // Given: real PostgreSQL, a transaction-proxied service, and a fake interactive console.
        char[] oldPassword = ("가".repeat(20) + "a".repeat(12)).toCharArray();
        char[] newPassword = "new-password-phrase".toCharArray();
        var entered = new ArrayDeque<char[]>();
        entered.add(oldPassword);
        var output = new StringWriter();
        var encoder = new BCryptPasswordEncoder(12);

        try (var context = applicationContext();
                var listener = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var users = context.getBean(AdminUserService.class);
            var command = command(users, entered, output);

            // When: an operator creates the account through the command surface.
            command.execute(List.of("create-admin", "Operator@EXAMPLE.COM"));

            // Then: canonical persistence and exact cost-12 BCrypt behavior are observable.
            UUID targetUser = jdbc.queryForObject(
                    "select id from admin_user where email = 'operator@example.com'", UUID.class);
            String oldHash = jdbc.queryForObject(
                    "select password_hash from admin_user where id = ?", String.class, targetUser);
            assertThat(oldHash).startsWith("$2a$12$");
            assertThat(encoder.matches(new String(oldPassword), oldHash)).isTrue();
            assertThat(encoder.matches(new String(newPassword), oldHash)).isFalse();

            // Given: rejected inputs and a case variant of the same canonical account.
            String unchangedHash = oldHash;
            entered.add("duplicate-password".toCharArray());
            assertThatThrownBy(() -> command.execute(List.of("create-admin", "OPERATOR@example.com")))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertRejectedWithoutMutation(command, entered, unchangedHash);
            assertThat(jdbc.queryForObject("select count(*) from admin_user", Integer.class)).isOne();

            entered.add("other-password".toCharArray());
            command.execute(List.of("create-admin", "other@example.com"));
            UUID otherUser = jdbc.queryForObject(
                    "select id from admin_user where email = 'other@example.com'", UUID.class);
            insertSession("target-session-one", targetUser.toString());
            insertSession("target-session-two", targetUser.toString());
            insertSession("other-admin-session", otherUser.toString());
            insertSession("signer-session", null);
            listener.createStatement().execute("listen narae_admin_session_invalidated");
            entered.add(newPassword);

            // When: the operator resets the target password.
            command.execute(List.of("reset-password", "OPERATOR@EXAMPLE.COM"));
            listener.createStatement().execute("select 1");

            // Then: only target administrator sessions disappear and each publishes after commit.
            String resetHash = jdbc.queryForObject(
                    "select password_hash from admin_user where id = ?", String.class, targetUser);
            assertThat(encoder.matches(new String(oldPassword), resetHash)).isFalse();
            assertThat(encoder.matches(new String(newPassword), resetHash)).isTrue();
            assertThat(sessionIds()).containsExactlyInAnyOrder("other-admin-session", "signer-session");
            assertThat(notificationPayloads(listener))
                    .hasSize(2)
                    .allSatisfy(payload -> assertThat(payload).contains(targetUser.toString(), "PASSWORD_RESET"))
                    .anySatisfy(payload -> assertThat(payload).contains("target-session-one"))
                    .anySatisfy(payload -> assertThat(payload).contains("target-session-two"));
            assertThat(output.toString())
                    .contains("Administrator created: operator@example.com", "sessions invalidated: 2")
                    .doesNotContain(new String(oldPassword), new String(newPassword));
        }
    }

    private void assertRejectedWithoutMutation(
            AdminCommand command, ArrayDeque<char[]> entered, String unchangedHash) {
        int consoleEntries = entered.size();
        assertThatThrownBy(() -> command.execute(List.of("create-admin", " admin@example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(entered).hasSize(consoleEntries);

        entered.add("12345678901".toCharArray());
        assertThatThrownBy(() -> command.execute(List.of("reset-password", "operator@example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        entered.add(("가".repeat(21) + "a".repeat(10)).toCharArray());
        assertThatThrownBy(() -> command.execute(List.of("reset-password", "operator@example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command.execute(List.of(
                        "reset-password", "operator@example.com", "password-must-not-be-an-argument")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments(
                        "reset-password", "operator@example.com", "--password=forbidden")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments("--password=forbidden")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject(
                        "select password_hash from admin_user where email = 'operator@example.com'", String.class))
                .isEqualTo(unchangedHash);
        assertThat(jdbc.queryForObject("select count(*) from spring_session", Integer.class)).isZero();
    }

    private AdminCommand command(AdminUserService users, ArrayDeque<char[]> entered, StringWriter output) {
        return new AdminCommand(users, prompt -> entered.remove().clone(), new PrintWriter(output, true));
    }

    private AnnotationConfigApplicationContext applicationContext() {
        var context = new AnnotationConfigApplicationContext();
        context.register(TestConfiguration.class);
        context.registerBean(DataSource.class, this::dataSource);
        context.refresh();
        return context;
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void insertSession(String sessionId, String principalName) {
        long now = Instant.now().toEpochMilli();
        jdbc.update(
                "insert into spring_session (primary_id, session_id, creation_time, last_access_time, "
                        + "max_inactive_interval, expiry_time, principal_name) values (?, ?, ?, ?, ?, ?, ?)",
                sessionId, sessionId, now, now, 43_200, now + 43_200_000, principalName);
    }

    private List<String> sessionIds() {
        return jdbc.queryForList("select trim(session_id) from spring_session", String.class);
    }

    private static List<String> notificationPayloads(Connection connection) throws Exception {
        Class<?> pgConnection = Class.forName("org.postgresql.PGConnection");
        Object unwrapped = connection.unwrap(pgConnection);
        Object[] notifications = (Object[]) pgConnection.getMethod("getNotifications").invoke(unwrapped);
        if (notifications == null) {
            return List.of();
        }
        return Stream.of(notifications)
                .map(notification -> {
                    try {
                        return (String) notification.getClass().getMethod("getParameter").invoke(notification);
                    } catch (ReflectiveOperationException exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .toList();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfiguration {
        @Bean
        JdbcOperations jdbc(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactions(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcIndexedSessionRepository sessionRepository(
                JdbcOperations jdbc, PlatformTransactionManager transactions) {
            return new JdbcIndexedSessionRepository(jdbc, new TransactionTemplate(transactions));
        }

        @Bean
        AdminSessionInvalidationPublisher publisher(JdbcOperations jdbc) {
            return new AdminSessionInvalidationPublisher(jdbc, new ObjectMapper());
        }

        @Bean
        AdminSessionInvalidator invalidator(
                JdbcIndexedSessionRepository sessions, AdminSessionInvalidationPublisher publisher) {
            return new AdminSessionInvalidator(sessions, publisher);
        }

        @Bean
        AdminUserService users(JdbcOperations jdbc, AdminSessionInvalidator sessions) {
            return new AdminUserService(jdbc, sessions);
        }
    }
}
