package com.naraesigning.background;

import com.naraesigning.config.PlatformProperties;
import com.naraesigning.crypto.VersionedCryptoService;
import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("spring.datasource.url")
class BackgroundConfiguration {
    @Bean
    BackgroundImageProcessor backgroundImageProcessor() {
        return new BackgroundImageProcessor();
    }

    @Bean
    BackgroundObjectStore backgroundObjectStore(MinioClient minio, PlatformProperties properties) {
        return new MinioBackgroundObjectStore(minio, properties.minioBucket());
    }

    @Bean
    BackgroundAssetRepository backgroundAssetRepository(JdbcOperations jdbc) {
        return new JdbcBackgroundAssetRepository(jdbc);
    }

    @Bean
    BackgroundAssetService backgroundAssetService(
            BackgroundImageProcessor processor,
            BackgroundObjectStore objects,
            BackgroundAssetRepository repository,
            VersionedCryptoService crypto,
            PlatformTransactionManager transactionManager) {
        return new BackgroundAssetService(processor, objects, repository, crypto,
                new TransactionTemplate(transactionManager));
    }

    @Bean
    BackgroundCleanupWorker backgroundCleanupWorker(
            BackgroundAssetRepository repository,
            BackgroundObjectStore objects,
            VersionedCryptoService crypto) {
        return new BackgroundCleanupWorker(repository, objects, crypto);
    }

    @Bean
    BackgroundCleanupScheduler backgroundCleanupScheduler(BackgroundCleanupWorker worker) {
        return new BackgroundCleanupScheduler(worker);
    }
}
