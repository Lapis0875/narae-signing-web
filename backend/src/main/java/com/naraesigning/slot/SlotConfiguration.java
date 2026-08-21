package com.naraesigning.slot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("spring.datasource.url")
class SlotConfiguration {
    @Bean
    SlotRepository slotRepository(JdbcOperations jdbc, PlatformTransactionManager transactionManager) {
        return new JdbcSlotRepository(jdbc, new TransactionTemplate(transactionManager));
    }

    @Bean
    SlotService slotService(SlotRepository repository) {
        return new SlotService(repository);
    }
}
