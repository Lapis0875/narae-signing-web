package com.naraesigning.signature;

import com.naraesigning.crypto.VersionedCryptoService;
import com.naraesigning.realtime.LiveSignatureRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("spring.datasource.url")
class SignatureSubmitConfiguration {
    @Bean
    SignatureSubmissionRepository signatureSubmissionRepository(
            JdbcOperations jdbc, TransactionOperations transactions) {
        return new JdbcSignatureSubmissionRepository(jdbc, transactions);
    }

    @Bean
    SignatureSubmissionEvents signatureSubmissionEvents(ApplicationEventPublisher events) {
        return new AfterCommitSignatureSubmissionEvents(events);
    }

    @Bean
    SignatureWireParser signatureWireParser() {
        return new SignatureWireParser();
    }

    @Bean
    SignatureSubmitService signatureSubmitService(
            SignatureSubmissionRepository repository,
            VersionedCryptoService crypto,
            SignatureSubmissionEvents events) {
        return new SignatureSubmitService(repository, crypto, events);
    }

    @Bean
    LiveSignatureService liveSignatureService(
            SignatureSubmissionRepository repository, LiveSignatureRegistry drafts) {
        return new LiveSignatureService(repository, drafts);
    }
}
