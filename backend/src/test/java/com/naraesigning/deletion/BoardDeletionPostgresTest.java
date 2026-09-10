package com.naraesigning.deletion;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.VersionedCryptoService;
import com.naraesigning.realtime.BoardMutationEvent;
import com.naraesigning.realtime.LiveSignatureRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

class BoardDeletionPostgresTest {
    private static final String LABEL_KEY = "narae.task";
    private static final String LABEL_VALUE = "task28-deletion";
    private static final Duration LEASE = Duration.ofSeconds(30);

    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    @Test
    void legacyV3ProcessingJobMigratesWithoutDataLossAndBecomesReclaimable() {
        var postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withLabel(LABEL_KEY, LABEL_VALUE);
        try {
            postgres.start();
            var v3 = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("3"))
                    .load();
            assertThat(v3.migrate().migrationsExecuted).isEqualTo(3);
            var setup = new JdbcTemplate(dataSource(postgres, "task28-v3-upgrade"));
            var ownerId = UUID.randomUUID();
            var boardId = UUID.randomUUID();
            var jobId = UUID.randomUUID();
            var crypto = new VersionedCryptoService(Map.of(1, new byte[32]), 1);
            var jobKey = crypto.encrypt("private/task28-legacy-object".getBytes(StandardCharsets.UTF_8),
                    CryptoContext.field("board-deletion-job", jobId.toString(), "object-key"));
            setup.update("""
                    insert into admin_user (id, email, password_hash, status)
                    values (?, ?, 'not-a-real-password-hash', 'ACTIVE')
                    """, ownerId, ownerId + "@example.invalid");
            setup.update("""
                    insert into board (id, owner_id, title, status, canvas_width, canvas_height,
                        share_token_lookup_hash, share_token_ciphertext, share_token_nonce,
                        share_token_key_version)
                    values (?, ?, 'task28 legacy migration', 'DELETING', 800, 600, ?, ?, ?, 1)
                    """, boardId, ownerId, bytes(41), bytes(42), bytes(43));
            setup.update("""
                    insert into board_deletion_job (id, board_id, encrypted_object_key, object_key_nonce,
                        object_key_key_version, reason, status, attempt_count, next_attempt_at, last_error)
                    values (?, ?, ?, ?, 1, 'BOARD_DELETE', 'PROCESSING', 7,
                        timestamp with time zone '2026-08-21 00:00:00+00', 'OBJECT_DELETE_FAILED')
                    """, jobId, boardId, jobKey.ciphertext(), jobKey.nonce());

            var v4 = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("4"))
                    .load();
            assertThat(v4.migrate().migrationsExecuted).isOne();

            var migrated = setup.queryForMap("select * from board_deletion_job where id = ?", jobId);
            assertThat(migrated.get("board_id")).isEqualTo(boardId);
            assertThat((byte[]) migrated.get("encrypted_object_key")).isEqualTo(jobKey.ciphertext());
            assertThat((byte[]) migrated.get("object_key_nonce")).isEqualTo(jobKey.nonce());
            assertThat(migrated.get("object_key_key_version")).isEqualTo(1);
            assertThat(migrated.get("attempt_count")).isEqualTo(7);
            assertThat(migrated.get("last_error")).isEqualTo("OBJECT_DELETE_FAILED");
            assertThat(migrated.get("reason")).isEqualTo("BOARD_DELETE");
            assertThat(migrated.get("status")).isEqualTo("PENDING");
            assertThat(((Timestamp) migrated.get("next_attempt_at")).toInstant())
                    .isEqualTo(Instant.parse("2026-08-21T00:00:00Z"));
            assertThat(migrated.get("lease_token")).isNull();
            assertThat(migrated.get("lease_expires_at")).isNull();

            var token = UUID.randomUUID();
            var now = Instant.parse("2100-01-01T00:00:00Z");
            var reclaimer = store(postgres, "task28-v4-reclaimer", crypto);
            var claimed = reclaimer.claim(token, now, LEASE);
            assertThat(claimed).singleElement().satisfies(job -> {
                assertThat(job.id()).isEqualTo(jobId);
                assertThat(job.attemptCount()).isEqualTo(8);
                assertThat(job.leaseToken()).isEqualTo(token);
            });
            assertThatThrownBy(() -> setup.update(
                    "update board_deletion_job set lease_token = null where id = ?", jobId))
                    .hasMessageContaining("board_deletion_job_lease_check");
            assertThatThrownBy(() -> setup.update(
                    "update board_deletion_job set status = 'PENDING' where id = ?", jobId))
                    .hasMessageContaining("board_deletion_job_lease_check");
            reclaimer.retry(jobId, token, now, 8);
            var retried = setup.queryForMap(
                    "select status, attempt_count, lease_token, lease_expires_at, last_error "
                            + "from board_deletion_job where id = ?", jobId);
            assertThat(retried).containsEntry("status", "PENDING")
                    .containsEntry("attempt_count", 8)
                    .containsEntry("last_error", "OBJECT_DELETE_FAILED");
            assertThat(retried.get("lease_token")).isNull();
            assertThat(retried.get("lease_expires_at")).isNull();
        } finally {
            postgres.stop();
        }

        assertThat(DockerClientFactory.instance().client().listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(LABEL_KEY, LABEL_VALUE))
                .exec()).isEmpty();
    }

    @Test
    void v4MigratesAndRealConnectionsProvePostCommitEventAndLeaseReclaim() throws Exception {
        var postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withLabel(LABEL_KEY, LABEL_VALUE);
        try {
            postgres.start();
            var flyway = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);
            var setup = new JdbcTemplate(dataSource(postgres, "task28-setup"));

            assertPhaseAEventOnlyAfterCommit(setup, postgres);
            assertTwoPollersLeaseAndReclaim(setup, postgres);
        } finally {
            postgres.stop();
        }

        assertThat(DockerClientFactory.instance().client().listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(LABEL_KEY, LABEL_VALUE))
                .exec()).isEmpty();
    }

    private static void assertPhaseAEventOnlyAfterCommit(JdbcTemplate setup,
            PostgreSQLContainer<?> postgres) {
        var ownerId = UUID.randomUUID();
        var boardId = UUID.randomUUID();
        var assetId = UUID.randomUUID();
        var crypto = new VersionedCryptoService(Map.of(1, new byte[32]), 1);
        var assetKey = crypto.encrypt("private/task28-object".getBytes(StandardCharsets.UTF_8),
                CryptoContext.field("background-asset", assetId.toString(), "object-key"));
        seedBoardAndAsset(setup, ownerId, boardId, assetId, assetKey.ciphertext(), assetKey.nonce());
        var store = store(postgres, "task28-phase-a", crypto);
        var observer = new JdbcTemplate(dataSource(postgres, "task28-event-observer"));
        var published = new ArrayList<BoardMutationEvent>();
        var service = new BoardDeletionService(store, event -> {
            assertThat(observer.queryForObject("select status from board where id = ?", String.class, boardId))
                    .isEqualTo("DELETING");
            assertThat(observer.queryForObject(
                    "select count(*) from board_deletion_job where board_id = ?", Integer.class, boardId))
                    .isOne();
            published.add((BoardMutationEvent) event);
        }, mock(LiveSignatureRegistry.class));

        setup.execute("""
                create function task28_reject_job() returns trigger language plpgsql as $$
                begin raise exception 'task28 injected rollback'; end $$
                """);
        setup.execute("""
                create trigger task28_reject_job before insert on board_deletion_job
                for each row execute function task28_reject_job()
                """);
        assertThatThrownBy(() -> service.delete(ownerId, boardId))
                .hasMessageContaining("task28 injected rollback");
        assertThat(setup.queryForObject("select status from board where id = ?", String.class, boardId))
                .isEqualTo("OPEN");
        assertThat(setup.queryForObject(
                "select count(*) from board_deletion_job where board_id = ?", Integer.class, boardId)).isZero();
        assertThat(published).isEmpty();
        setup.execute("drop trigger task28_reject_job on board_deletion_job");
        setup.execute("drop function task28_reject_job()");

        service.delete(ownerId, boardId);
        service.delete(ownerId, boardId);

        assertThat(published).containsExactly(new BoardMutationEvent(boardId, "board-deleted"));
        setup.update("update board set background_asset_id = null where id = ?", boardId);
        setup.update("delete from board_deletion_job where board_id = ?", boardId);
        setup.update("delete from board where id = ?", boardId);
        setup.update("delete from admin_user where id = ?", ownerId);
    }

    private static void assertTwoPollersLeaseAndReclaim(JdbcTemplate setup,
            PostgreSQLContainer<?> postgres) throws Exception {
        var boardId = UUID.randomUUID();
        var ownerId = UUID.randomUUID();
        setup.update("""
                insert into admin_user (id, email, password_hash, status)
                values (?, ?, 'not-a-real-password-hash', 'ACTIVE')
                """, ownerId, ownerId + "@example.invalid");
        setup.update("""
                insert into board (id, owner_id, title, status, canvas_width, canvas_height,
                    share_token_lookup_hash, share_token_ciphertext, share_token_nonce, share_token_key_version)
                values (?, ?, 'task28 lease board', 'DELETING', 800, 600, ?, ?, ?, 1)
                """, boardId, ownerId, bytes(21), bytes(22), bytes(23));
        var jobId = UUID.randomUUID();
        var crypto = new VersionedCryptoService(Map.of(1, new byte[32]), 1);
        var jobKey = crypto.encrypt("private/task28-lease-object".getBytes(StandardCharsets.UTF_8),
                CryptoContext.field("board-deletion-job", jobId.toString(), "object-key"));
        setup.update("""
                insert into board_deletion_job (id, board_id, encrypted_object_key, object_key_nonce,
                    object_key_key_version, reason, status, attempt_count)
                values (?, ?, ?, ?, 1, 'BOARD_DELETE', 'PENDING', 0)
                """, jobId, boardId, jobKey.ciphertext(), jobKey.nonce());
        var first = store(postgres, "task28-poller-one", crypto);
        var second = store(postgres, "task28-poller-two", crypto);
        var now = Instant.parse("2026-08-22T00:00:00Z");
        var firstToken = UUID.randomUUID();
        var secondToken = UUID.randomUUID();
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstClaim = executor.submit(() -> {
                ready.countDown();
                start.await();
                return first.claim(firstToken, now, LEASE);
            });
            var secondClaim = executor.submit(() -> {
                ready.countDown();
                start.await();
                return second.claim(secondToken, now, LEASE);
            });
            assertThat(ready.await(5, SECONDS)).isTrue();
            start.countDown();
            var firstJobs = firstClaim.get(10, SECONDS);
            var secondJobs = secondClaim.get(10, SECONDS);

            assertThat(firstJobs.size() + secondJobs.size()).isOne();
            var winner = firstJobs.isEmpty() ? second : first;
            var loser = firstJobs.isEmpty() ? first : second;
            var winnerToken = firstJobs.isEmpty() ? secondToken : firstToken;
            var loserToken = firstJobs.isEmpty() ? firstToken : secondToken;
            assertThat(loser.claim(loserToken, now.plusSeconds(1), LEASE)).isEmpty();
            assertThat(winner.renew(jobId, winnerToken, now.plusSeconds(10), LEASE)).isTrue();
            assertThat(loser.claim(loserToken, now.plus(LEASE), LEASE)).isEmpty();

            var reclaimed = loser.claim(loserToken, now.plusSeconds(40), LEASE);

            assertThat(reclaimed).singleElement().satisfies(job -> {
                assertThat(job.id()).isEqualTo(jobId);
                assertThat(job.attemptCount()).isEqualTo(2);
                assertThat(job.leaseToken()).isEqualTo(loserToken);
                assertThat(job.leaseExpiresAt()).isEqualTo(now.plusSeconds(70));
            });
            assertThat(setup.queryForObject(
                    "select attempt_count from board_deletion_job where id = ?", Integer.class, jobId))
                    .isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    private static JdbcBoardDeletionStore store(PostgreSQLContainer<?> postgres, String name,
            VersionedCryptoService crypto) {
        var dataSource = dataSource(postgres, name);
        return new JdbcBoardDeletionStore(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), crypto);
    }

    private static DataSource dataSource(PostgreSQLContainer<?> postgres, String name) {
        var dataSource = new DriverManagerDataSource();
        dataSource.setUrl(postgres.getJdbcUrl() + "&ApplicationName=" + name);
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    private static void seedBoardAndAsset(JdbcTemplate jdbc, UUID ownerId, UUID boardId, UUID assetId,
            byte[] ciphertext, byte[] nonce) {
        jdbc.update("""
                insert into admin_user (id, email, password_hash, status)
                values (?, ?, 'not-a-real-password-hash', 'ACTIVE')
                """, ownerId, ownerId + "@example.invalid");
        jdbc.update("""
                insert into board (id, owner_id, title, status, canvas_width, canvas_height,
                    share_token_lookup_hash, share_token_ciphertext, share_token_nonce, share_token_key_version)
                values (?, ?, 'task28 phase a board', 'OPEN', 800, 600, ?, ?, ?, 1)
                """, boardId, ownerId, bytes(1), bytes(2), bytes(3));
        jdbc.update("""
                insert into background_asset (id, board_id, encrypted_object_key, object_key_nonce,
                    object_key_key_version, display_width, display_height, mime_type)
                values (?, ?, ?, ?, 1, 800, 600, 'image/png')
                """, assetId, boardId, ciphertext, nonce);
        jdbc.update("update board set background_asset_id = ? where id = ?", assetId, boardId);
    }

    private static byte[] bytes(int value) {
        return new byte[] {(byte) value};
    }
}
