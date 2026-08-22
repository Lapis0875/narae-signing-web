package com.naraesigning.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class BoardSnapshotServiceConditionTest {
    @Test
    void isConditionalOnDatasourceUrl() {
        var condition = BoardSnapshotService.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.value()).containsExactly("spring.datasource.url");
        assertThat(condition.name()).isEmpty();
    }
}
