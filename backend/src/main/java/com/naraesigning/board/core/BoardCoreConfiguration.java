package com.naraesigning.board.core;

import com.naraesigning.crypto.VersionedCryptoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("spring.datasource.url")
class BoardCoreConfiguration {
    @Bean
    BoardRepository boardRepository(JdbcOperations jdbc) {
        return new JdbcBoardRepository(jdbc);
    }

    @Bean
    BoardService boardService(
            BoardRepository repository,
            VersionedCryptoService crypto,
            PlatformTransactionManager transactionManager) {
        return new BoardService(repository, crypto, new TransactionTemplate(transactionManager));
    }
}
