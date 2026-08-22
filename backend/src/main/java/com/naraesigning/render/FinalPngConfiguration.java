package com.naraesigning.render;

import com.naraesigning.background.BackgroundObjectStore;
import com.naraesigning.crypto.VersionedCryptoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("spring.datasource.url")
class FinalPngConfiguration {
    @Bean
    FinalPngSnapshotRepository finalPngSnapshotRepository(
            JdbcOperations jdbc, PlatformTransactionManager transactionManager) {
        return new JdbcFinalPngSnapshotRepository(jdbc, new TransactionTemplate(transactionManager));
    }

    @Bean
    FinalPngService finalPngService(FinalPngSnapshotRepository snapshots,
            BackgroundObjectStore objects, VersionedCryptoService crypto) {
        return new FinalPngService(snapshots, objects, crypto);
    }
}
