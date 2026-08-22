package com.naraesigning.board.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.session.web.http.SessionRepositoryFilter;

class AdminBoardFilterOrderTest {
    @Test
    void runsImmediatelyAfterSessionRepositoryFilter() {
        assertThat(AdminBoardFilter.class.getAnnotation(Order.class).value())
                .isEqualTo(SessionRepositoryFilter.DEFAULT_ORDER + 1);
    }
}
