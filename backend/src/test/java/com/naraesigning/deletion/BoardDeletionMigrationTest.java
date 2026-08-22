package com.naraesigning.deletion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BoardDeletionMigrationTest {
    @Test
    void givenMigrationWhenInspectedThenDurableLeaseAndClaimIndexExist() throws Exception {
        // Given
        var migration = Path.of("src/main/resources/db/migration/V4__deletion_job_lease.sql");

        // When
        var sql = Files.readString(migration);

        // Then
        assertThat(sql).contains("lease_token", "lease_expires_at", "board_deletion_job_claim_idx");
        assertThat(sql).contains("WHERE reason = 'BOARD_DELETE' AND status = 'PROCESSING'");
        assertThat(sql.indexOf("SET status = 'PENDING'"))
                .isLessThan(sql.indexOf("ADD CONSTRAINT board_deletion_job_lease_check"));
        assertThat(sql).doesNotContain("object_key VARCHAR", "last_error = '");
    }
}
