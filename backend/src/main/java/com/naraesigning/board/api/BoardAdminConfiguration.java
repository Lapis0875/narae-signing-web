package com.naraesigning.board.api;

import com.naraesigning.background.BackgroundAssetService;
import com.naraesigning.board.core.BoardService;
import com.naraesigning.session.SessionCookieActions;
import com.naraesigning.realtime.LiveSignatureRegistry;
import com.naraesigning.slot.SlotService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("spring.datasource.url")
class BoardAdminConfiguration {
    @Bean
    BoardAdminFacade boardAdminFacade(BoardService boards, SlotService slots,
            BackgroundAssetService backgrounds, JdbcOperations jdbc,
            PlatformTransactionManager transactionManager, ApplicationEventPublisher events,
            LiveSignatureRegistry drafts) {
        return new JdbcBoardAdminFacade(boards, slots, backgrounds, jdbc,
                new TransactionTemplate(transactionManager), events, drafts);
    }

    @Bean
    AdminBoardFilter adminBoardFilter(BoardAdminFacade facade, SessionCookieActions cookies, Clock authClock) {
        return new AdminBoardFilter(facade, cookies, authClock);
    }
}
