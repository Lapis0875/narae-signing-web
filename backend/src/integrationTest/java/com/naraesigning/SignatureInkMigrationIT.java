package com.naraesigning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SignatureInkMigrationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void characterizesLegacyV5BoardRosterAndSlotPayloads() throws Exception {
        resetSchema();
        migrateToV5();

        try (var connection = connection()) {
            insertLegacyFixture(connection);

            assertThat(capture(connection)).isEqualTo(new LegacyCapture(
                    3, 5, 5,
                    "b35f180722ac00c9a07cac2c988460d927b8a794090b564cd39793608efeb2c3",
                    4, 9, "40000000-0000-0000-0000-000000000001@2030-01-01T00:00:00Z",
                    "0.10000000,0.20000000,0.30000000,0.40000000"));
        }
    }

    @Test
    void upgradesV5WithBoardInkDefaultsConstraintsAndPayloadPreservation() throws Exception {
        resetSchema();
        migrateToV5();
        LegacyCapture before;
        try (var connection = connection()) {
            insertLegacyFixture(connection);
            before = capture(connection);
        }

        var first = flywayLatest().migrate();
        var second = flywayLatest().migrate();

        assertThat(first.migrationsExecuted).isOne();
        assertThat(second.migrationsExecuted).isZero();
        try (var connection = connection(); var statement = connection.createStatement()) {
            assertThat(capture(connection)).isEqualTo(before);
            assertThat(count(connection, "board")).isEqualTo(3);
            assertThat(count(connection, "roster_entry")).isEqualTo(5);
            assertThat(count(connection, "signature_slot")).isEqualTo(5);
            try (var colors = statement.executeQuery(
                    "select signature_ink_color from board order by id")) {
                var values = new java.util.ArrayList<String>();
                while (colors.next()) values.add(colors.getString(1));
                assertThat(values).containsExactly("black", "black", "black");
            }
            assertThat(columnExists(connection, "signature_slot", "background_color")).isFalse();
            assertThat(columnExists(connection, "board", "signature_ink_color")).isTrue();
            try (var column = statement.executeQuery("""
                    select is_nullable, column_default from information_schema.columns
                    where table_schema='public' and table_name='board' and column_name='signature_ink_color'
                    """)) {
                assertThat(column.next()).isTrue();
                assertThat(column.getString("is_nullable")).isEqualTo("NO");
                assertThat(column.getString("column_default")).contains("'black'");
            }
            try (var constraint = statement.executeQuery("""
                    select pg_get_constraintdef(oid) definition from pg_constraint
                    where conrelid='board'::regclass and contype='c'
                      and pg_get_constraintdef(oid) like '%signature_ink_color%'
                    """)) {
                assertThat(constraint.next()).isTrue();
                assertThat(constraint.getString("definition")).contains("black", "white");
            }
        }

        assertRejectedColor(null);
        assertRejectedColor("red");
        assertRejectedColor("WHITE");
        assertAcceptedColor("black");
        assertAcceptedColor("white");

        try (var connection = connection()) {
            var beforeRollback = count(connection, "board");
            connection.setAutoCommit(false);
            assertThatThrownBy(() -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("update board set signature_ink_color='red' where id="
                            + "'10000000-0000-0000-0000-000000000001'");
                }
            }).isInstanceOf(java.sql.SQLException.class);
            connection.rollback();
            connection.setAutoCommit(true);
            assertThat(count(connection, "board")).isEqualTo(beforeRollback);
            try (var statement = connection.createStatement(); var color = statement.executeQuery(
                    "select signature_ink_color from board where id='10000000-0000-0000-0000-000000000001'")) {
                assertThat(color.next()).isTrue();
                assertThat(color.getString(1)).isEqualTo("white");
            }
        }
        System.out.println("QA signature_ink_migration=v6 boards=3 roster=5 slots=5 "
                + "ciphertext_hash_equal=true ciphertext_bytes_equal=true lease_revision_geometry_equal=true "
                + "slot_background_absent=true board_color_default=black not_null=true check=black|white "
                + "second_migrate=0 invalid_rollback_preserved=true");
    }

    @Test
    void migratesAnEmptyDatabaseThroughV6() throws Exception {
        resetSchema();
        var first = flywayLatest().migrate();
        var second = flywayLatest().migrate();

        assertThat(first.migrationsExecuted).isEqualTo(6);
        assertThat(second.migrationsExecuted).isZero();
        try (var connection = connection()) {
            assertThat(columnExists(connection, "board", "signature_ink_color")).isTrue();
            assertThat(columnExists(connection, "signature_slot", "background_color")).isFalse();
        }
    }

    private static void resetSchema() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("drop schema public cascade");
            statement.execute("create schema public");
        }
    }

    private static void migrateToV5() {
        flyway("5").migrate();
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static Flyway flywayLatest() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void insertLegacyFixture(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    insert into admin_user(id,email,password_hash,status) values
                    ('00000000-0000-0000-0000-000000000001','fixture@example.invalid','synthetic','ACTIVE')
                    """);
            statement.execute("""
                    insert into board(id,owner_id,title,status,canvas_width,canvas_height,
                        share_token_lookup_hash,share_token_ciphertext,share_token_nonce,share_token_key_version)
                    values
                    ('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Draft','DRAFT',1600,900,decode('01','hex'),decode('a1a2','hex'),decode('b1','hex'),1),
                    ('10000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','Open','OPEN',1200,800,decode('02','hex'),decode('a3a4','hex'),decode('b2','hex'),1),
                    ('10000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001','Closed','CLOSED',800,600,decode('03','hex'),decode('a5a6','hex'),decode('b3','hex'),1)
                    """);
            statement.execute("""
                    insert into roster_entry(id,board_id,encrypted_identity,identity_nonce,identity_key_version,identity_hmac)
                    values
                    ('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001',decode('11','hex'),decode('21','hex'),1,decode('31','hex')),
                    ('20000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001',decode('12','hex'),decode('22','hex'),1,decode('32','hex')),
                    ('20000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000002',decode('13','hex'),decode('23','hex'),1,decode('33','hex')),
                    ('20000000-0000-0000-0000-000000000004','10000000-0000-0000-0000-000000000003',decode('14','hex'),decode('24','hex'),1,decode('34','hex')),
                    ('20000000-0000-0000-0000-000000000005','10000000-0000-0000-0000-000000000003',decode('15','hex'),decode('25','hex'),1,decode('35','hex'))
                    """);
            statement.execute("""
                    insert into signature_slot(id,roster_entry_id,placement_status,x,y,width,height,
                        background_color,encrypted_strokes,strokes_nonce,strokes_key_version,slot_revision,
                        active_signer_claim,active_signer_claim_expires_at)
                    values
                    ('30000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','PLACED',.1,.2,.3,.4,null,decode('c1c2','hex'),decode('d1','hex'),1,9,'40000000-0000-0000-0000-000000000001','2030-01-01T00:00:00Z'),
                    ('30000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000002','UNPLACED',null,null,null,null,'white',null,null,null,0,null,null),
                    ('30000000-0000-0000-0000-000000000003','20000000-0000-0000-0000-000000000003','UNPLACED',null,null,null,null,'WHITE',null,null,null,0,null,null),
                    ('30000000-0000-0000-0000-000000000004','20000000-0000-0000-0000-000000000004','UNPLACED',null,null,null,null,'transparent',null,null,null,0,null,null),
                    ('30000000-0000-0000-0000-000000000005','20000000-0000-0000-0000-000000000005','UNPLACED',null,null,null,null,'other',null,null,null,0,null,null)
                    """);
        }
    }

    private static LegacyCapture capture(Connection connection) throws Exception {
        var boardCount = count(connection, "board");
        var rosterCount = count(connection, "roster_entry");
        var slotCount = count(connection, "signature_slot");
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                select encode(share_token_ciphertext, 'hex') board_ciphertext,
                       encode(encrypted_strokes, 'hex') strokes_ciphertext,
                       octet_length(share_token_ciphertext) + octet_length(encrypted_strokes) bytes,
                       slot_revision, active_signer_claim, active_signer_claim_expires_at,
                       x, y, width, height
                from board join roster_entry on roster_entry.board_id = board.id
                join signature_slot on signature_slot.roster_entry_id = roster_entry.id
                where signature_slot.id = '30000000-0000-0000-0000-000000000001'
                """)) {
            assertThat(result.next()).isTrue();
            var payload = result.getString("board_ciphertext") + ":" + result.getString("strokes_ciphertext");
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.US_ASCII)));
            var geometry = String.join(",", result.getBigDecimal("x").toPlainString(),
                    result.getBigDecimal("y").toPlainString(), result.getBigDecimal("width").toPlainString(),
                    result.getBigDecimal("height").toPlainString());
            var lease = result.getObject("active_signer_claim") + "@"
                    + result.getTimestamp("active_signer_claim_expires_at").toInstant();
            return new LegacyCapture(boardCount, rosterCount, slotCount, hash,
                    result.getInt("bytes"), result.getLong("slot_revision"), lease, geometry);
        }
    }

    private static int count(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("select count(*) from " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (var result = connection.getMetaData().getColumns(null, "public", table, column)) {
            return result.next();
        }
    }

    private static void assertRejectedColor(String color) throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            var value = color == null ? "null" : "'" + color + "'";
            assertThatThrownBy(() -> statement.execute("update board set signature_ink_color=" + value
                    + " where id='10000000-0000-0000-0000-000000000001'"))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    private static void assertAcceptedColor(String color) throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            assertThat(statement.executeUpdate("update board set signature_ink_color='" + color
                    + "' where id='10000000-0000-0000-0000-000000000001'")).isOne();
        }
    }

    private record LegacyCapture(
            int boards, int rosterEntries, int slots, String ciphertextHash, int ciphertextBytes,
            long revision, String lease, String geometry) {}
}
