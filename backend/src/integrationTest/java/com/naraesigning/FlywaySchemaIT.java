package com.naraesigning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

@Testcontainers
class FlywaySchemaIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "synthetic-test-user")
            .withEnv("MINIO_ROOT_PASSWORD", "synthetic-test-password")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forStatusCode(200));

    @Test
    void migratesBlankDatabaseAndEnforcesCoreConstraints() throws Exception {
        // Given: blank PostgreSQL and a ready MinIO-compatible object store.
        assertThat(MINIO.isRunning()).isTrue();
        var flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        // When: migrations run twice and a minimal board/roster/slot is inserted.
        var first = flyway.migrate();
        var second = flyway.migrate();
        var owner = UUID.randomUUID();
        var board = UUID.randomUUID();
        var roster = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO admin_user(id,email,password_hash,status) VALUES ('" + owner
                    + "','owner@example.invalid','synthetic-bcrypt','ACTIVE')");
            statement.execute("INSERT INTO board(id,owner_id,title,status,canvas_width,canvas_height,"
                    + "share_token_lookup_hash,share_token_ciphertext,share_token_nonce,share_token_key_version) VALUES ('"
                    + board + "','" + owner + "','Synthetic board','DRAFT',1600,900,decode('01','hex'),decode('02','hex'),decode('03','hex'),1)");
            statement.execute("INSERT INTO roster_entry(id,board_id,encrypted_identity,identity_nonce,identity_key_version,identity_hmac) VALUES ('"
                    + roster + "','" + board + "',decode('11','hex'),decode('12','hex'),1,decode('13','hex'))");
            statement.execute("INSERT INTO signature_slot(id,roster_entry_id,placement_status,x,y,width,height) VALUES ('"
                    + UUID.randomUUID() + "','" + roster + "','PLACED',0.1,0.1,0.3,0.2)");

            // Then: versions/tables exist, retry is clean, and duplicate identity is rejected.
            assertThat(first.migrationsExecuted).isEqualTo(5);
            assertThat(second.migrationsExecuted).isZero();
            try (var tables = connection.getMetaData().getTables(null, "public", "%", new String[] {"TABLE"})) {
                var names = new java.util.HashSet<String>();
                while (tables.next()) names.add(tables.getString("TABLE_NAME").toLowerCase());
                assertThat(names).containsAll(Set.of("admin_user", "board", "roster_entry", "signature_slot",
                        "background_asset", "board_deletion_job", "spring_session", "spring_session_attributes",
                        "admin_login_ip_window", "login_failure_state"));
            }
            try (var indexes = statement.executeQuery("SELECT indexname FROM pg_indexes WHERE schemaname='public'")) {
                var names = new java.util.HashSet<String>();
                while (indexes.next()) names.add(indexes.getString(1));
                assertThat(names).contains("board_owner_id_idx", "roster_entry_board_id_idx",
                        "background_asset_board_id_idx", "roster_entry_board_identity_hmac_key",
                        "admin_login_ip_window_window_started_at_idx", "login_failure_state_pkey",
                        "login_failure_state_updated_at_idx", "login_failure_state_locked_until_idx",
                        "signature_slot_active_signer_claim_expires_at_idx");
            }
            try (var columns = statement.executeQuery("SELECT table_name || '.' || column_name FROM information_schema.columns WHERE table_schema='public'")) {
                var names = new java.util.HashSet<String>();
                while (columns.next()) names.add(columns.getString(1));
                assertThat(names).contains(
                        "board.share_link_version", "board.share_token_lookup_hash", "board.share_token_ciphertext",
                        "board.share_token_nonce", "board.share_token_key_version", "signature_slot.slot_revision",
                        "background_asset.object_key_nonce", "background_asset.object_key_key_version",
                        "board_deletion_job.reason", "admin_login_ip_window.attempt_count",
                        "login_failure_state.locked_until", "signature_slot.active_signer_claim",
                        "signature_slot.active_signer_claim_expires_at");
            }
            assertThatThrownBy(() -> statement.execute("UPDATE signature_slot SET active_signer_claim='"
                    + UUID.randomUUID() + "' WHERE roster_entry_id='" + roster + "'"))
                    .hasMessageContaining("signature_slot_active_claim_check");
            assertThatThrownBy(() -> statement.execute("INSERT INTO roster_entry(id,board_id,encrypted_identity,identity_nonce,identity_key_version,identity_hmac) VALUES ('"
                    + UUID.randomUUID() + "','" + board + "',decode('21','hex'),decode('22','hex'),1,decode('13','hex'))"))
                    .hasMessageContaining("roster_entry_board_identity_hmac_key");
            assertThatThrownBy(() -> statement.execute("INSERT INTO board(id,owner_id,title,status,canvas_width,canvas_height,share_token_lookup_hash,share_token_ciphertext,share_token_nonce,share_token_key_version) VALUES ('"
                    + UUID.randomUUID() + "','" + owner + "','Invalid','UNKNOWN',1,1,decode('31','hex'),decode('32','hex'),decode('33','hex'),1)"))
                    .hasMessageContaining("board_status_check");
        }
    }

    @Test
    void keepsSessionSchemaInitializationOutOfProductionProfile() {
        // Given: integration tests explicitly opt into Spring Session initialization.
        var testSetting = sessionInitializationFor("test");

        // When: identical application configuration loads as production.
        var productionSetting = sessionInitializationFor("production");

        // Then: only test uses framework initialization; Flyway remains production owner.
        assertThat(testSetting).isEqualTo("always");
        assertThat(productionSetting).isEqualTo("never");
    }

    @Test
    void keepsInitialMigrationsForwardOnly() throws Exception {
        // Given: exact Flyway resources shipped by backend.
        var loader = FlywaySchemaIT.class.getClassLoader();
        var sql = new String(loader.getResourceAsStream("db/migration/V1__core_schema.sql").readAllBytes())
                + new String(loader.getResourceAsStream("db/migration/V2__spring_session.sql").readAllBytes())
                + new String(loader.getResourceAsStream("db/migration/V3__login_defense.sql").readAllBytes());

        // When: migration operations are normalized for contract inspection.
        var normalized = sql.toLowerCase(java.util.Locale.ROOT);

        // Then: initial schema contains no contracting/down-migration operation.
        assertThat(normalized).doesNotContain("drop table", "drop column", "rename column", "alter column");
    }

    private static String sessionInitializationFor(String profile) {
        var application = new SpringApplication(NaraeSigningApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles(profile);
        try (var context = application.run(
                "--spring.main.banner-mode=off",
                "--spring.main.lazy-initialization=true",
                "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "--app.public-origin=https://signing.example.invalid",
                "--app.minio-endpoint=http://minio.invalid",
                "--app.minio-bucket=synthetic-bucket",
                "--app.master-key-file=/synthetic/master-key",
                "--app.crypto-key-version=1")) {
            return context.getEnvironment().getRequiredProperty("spring.session.jdbc.initialize-schema");
        }
    }
}
