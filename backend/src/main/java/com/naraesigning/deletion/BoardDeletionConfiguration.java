package com.naraesigning.deletion;

import com.naraesigning.background.BackgroundObjectStore;
import com.naraesigning.crypto.VersionedCryptoService;
import com.naraesigning.realtime.LiveSignatureRegistry;
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
class BoardDeletionConfiguration {
    @Bean
    BoardDeletionStore boardDeletionStore(JdbcOperations jdbc, PlatformTransactionManager transactions,
            VersionedCryptoService crypto) {
        return new JdbcBoardDeletionStore(jdbc, new TransactionTemplate(transactions), crypto);
    }

    @Bean BoardDeletionService boardDeletionService(BoardDeletionStore store, ApplicationEventPublisher events,
            LiveSignatureRegistry drafts) {
        return new BoardDeletionService(store, events, drafts);
    }

    @Bean
    BoardDeletionWorker boardDeletionWorker(BoardDeletionStore store, BackgroundObjectStore objects,
            VersionedCryptoService crypto, Clock clock) {
        return new BoardDeletionWorker(store, objects, crypto, clock);
    }

    @Bean BoardDeletionScheduler boardDeletionScheduler(BoardDeletionWorker worker) {
        return new BoardDeletionScheduler(worker);
    }
}
