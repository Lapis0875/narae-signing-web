package com.naraesigning.render;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
final class JdbcFinalPngSnapshotRepositoryTest {
    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ROSTER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID SLOT = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private JdbcTemplate setup;

    @BeforeEach
    void migrateAndSeedClosedBoard() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        setup = new JdbcTemplate(dataSource("task27-setup"));
        setup.update("delete from board");
        setup.update("delete from admin_user");
        setup.update("""
                insert into admin_user (id, email, password_hash, status)
                values (?, 'task27@example.invalid', 'not-a-real-password-hash', 'ACTIVE')
                """, OWNER);
        setup.update("""
                insert into board (id, owner_id, title, status, canvas_width, canvas_height,
                                   share_token_lookup_hash, share_token_ciphertext,
                                   share_token_nonce, share_token_key_version)
                values (?, ?, 'identity-ui-sentinel', 'CLOSED', 800, 600, ?, ?, ?, 1)
                """, BOARD, OWNER, bytes(1), bytes(2), bytes(3));
        setup.update("""
                insert into roster_entry (id, board_id, encrypted_identity, identity_nonce,
                                          identity_key_version, identity_hmac)
                values (?, ?, ?, ?, 1, ?)
                """, ROSTER, BOARD, bytes(4), bytes(5), bytes(6));
        setup.update("""
                insert into signature_slot (id, roster_entry_id, placement_status, x, y, width,
                                            height, background_color)
                values (?, ?, 'PLACED', 0.12500000, 0.25000000, 0.25000000, 0.25000000, 'white')
                """, SLOT, ROSTER);
    }

    @Test
    void readsCurrentCommittedPlacementOnlyAfterClosedReopenChangeAndReclose() {
        var repository = repository("task27-lifecycle-reader");

        var firstClosed = repository.readClosed(OWNER, BOARD);
        setup.update("update board set status = 'OPEN' where id = ?", BOARD);
        assertThatThrownBy(() -> repository.readClosed(OWNER, BOARD))
                .isInstanceOfSatisfying(FinalPngException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FINAL_PNG_NOT_CLOSED"));
        setup.update("update signature_slot set x = 0.62500000 where id = ?", SLOT);
        setup.update("update board set status = 'CLOSED' where id = ?", BOARD);
        var currentClosed = repository.readClosed(OWNER, BOARD);

        assertThat(firstClosed.slots().getFirst().x()).isEqualByComparingTo("0.12500000");
        assertThat(currentClosed.slots().getFirst().x()).isEqualByComparingTo("0.62500000");
        assertThat(setup.queryForObject("select status from board where id = ?", String.class, BOARD))
                .isEqualTo("CLOSED");
    }

    @Test
    void repositoryReadWaitsForRealPostgresBoardRowLockThenCompletes() throws Exception {
        var holderJdbc = new JdbcTemplate(dataSource("task27-lock-holder"));
        var holderTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(holderJdbc.getDataSource()));
        var repository = repository("task27-lock-reader");
        var observer = new JdbcTemplate(dataSource("task27-lock-observer"));
        var holderLocked = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var readerStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var holder = executor.submit(() -> {
                holderTransaction.executeWithoutResult(status -> {
                    holderJdbc.queryForObject(
                            "select id from board where id = ? for update", UUID.class, BOARD);
                    holderLocked.countDown();
                    await(releaseHolder);
                });
                return null;
            });
            assertThat(holderLocked.await(10, SECONDS)).isTrue();

            var reader = executor.submit(() -> {
                readerStarted.countDown();
                return repository.readClosed(OWNER, BOARD);
            });
            assertThat(readerStarted.await(10, SECONDS)).isTrue();
            assertThat(awaitBlockedReader(observer)).isGreaterThan(0);
            assertThat(reader.isDone()).isFalse();

            releaseHolder.countDown();
            holder.get(10, SECONDS);
            assertThat(reader.get(10, SECONDS).slots().getFirst().x())
                    .isEqualByComparingTo("0.12500000");
        } finally {
            releaseHolder.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    private static int awaitBlockedReader(JdbcTemplate observer) throws InterruptedException {
        var deadline = System.nanoTime() + SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var blocked = observer.queryForObject("""
                    select count(*) from pg_stat_activity activity
                    where activity.application_name = 'task27-lock-reader'
                      and activity.wait_event_type = 'Lock'
                      and cardinality(pg_blocking_pids(activity.pid)) > 0
                    """, Integer.class);
            if (blocked != null && blocked > 0) return blocked;
            Thread.sleep(25);
        }
        return 0;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, SECONDS)) throw new IllegalStateException("lock release timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("lock holder interrupted", exception);
        }
    }

    private static JdbcFinalPngSnapshotRepository repository(String applicationName) {
        var source = dataSource(applicationName);
        return new JdbcFinalPngSnapshotRepository(
                new JdbcTemplate(source),
                new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    private static DataSource dataSource(String applicationName) {
        var separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + separator + "ApplicationName=" + applicationName,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static byte[] bytes(int value) {
        return new byte[] {(byte) value};
    }
}
