package com.naraesigning.roster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.crypto.VersionedCryptoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("spring.datasource.url")
class RosterConfiguration {
    @Bean
    RosterRepository rosterRepository(JdbcOperations jdbc) {
        return new JdbcRosterRepository(jdbc);
    }

    @Bean
    RosterService rosterService(
            RosterRepository repository,
            VersionedCryptoService crypto,
            PlatformTransactionManager transactionManager) {
        return new RosterService(repository, crypto, new TransactionTemplate(transactionManager));
    }

    @Bean
    RosterJsonParser rosterJsonParser(ObjectMapper mapper) {
        return new RosterJsonParser(mapper);
    }

    @Bean
    RosterCsvParser rosterCsvParser() {
        return new RosterCsvParser();
    }

    @Bean
    RosterXlsxParser rosterXlsxParser() {
        RosterXlsxParser.configureZipSafety();
        return new RosterXlsxParser();
    }
}
