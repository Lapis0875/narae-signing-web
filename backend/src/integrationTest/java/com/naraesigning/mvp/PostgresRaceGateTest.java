package com.naraesigning.mvp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class PostgresRaceGateTest {
    @Test
    void acceptsTwoAdvisoryWaitersAndRejectsSingleShapeWaiters() {
        // Given: PostgreSQL reports supported and unsupported lock-wait shapes.
        var twoAdvisoryWaiters = Map.of("advisory", 2);

        // When: the race gate evaluates those waiter counts.
        var acceptsTwoAdvisoryWaiters = PostgresRaceGate.hasTwoDatabaseWaiters(twoAdvisoryWaiters);

        // Then: two advisory waiters are sufficient, while incomplete shapes remain rejected.
        assertThat(acceptsTwoAdvisoryWaiters).isTrue();
        assertThat(PostgresRaceGate.hasTwoDatabaseWaiters(Map.of("advisory", 1, "transactionid", 1))).isTrue();
        assertThat(PostgresRaceGate.hasTwoDatabaseWaiters(Map.of("advisory", 1, "tuple", 1))).isTrue();
        assertThat(PostgresRaceGate.hasTwoDatabaseWaiters(Map.of("advisory", 1))).isFalse();
        assertThat(PostgresRaceGate.hasTwoDatabaseWaiters(Map.of("tuple", 2))).isFalse();
    }
}
