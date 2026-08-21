package com.naraesigning.signer.identify;

import com.naraesigning.board.core.BoardService;
import com.naraesigning.crypto.VersionedCryptoService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("spring.datasource.url")
class PublicIdentifyConfiguration {
    @Bean
    PublicIdentifyRepository publicIdentifyRepository(JdbcOperations jdbc) {
        return new JdbcPublicIdentifyRepository(jdbc);
    }

    @Bean
    PublicIdentifyRateLimiter publicIdentifyRateLimiter(Clock authClock) {
        return new PublicIdentifyRateLimiter(authClock);
    }

    @Bean
    PublicLinkLookup publicLinkLookup(BoardService boards, PublicIdentifyRepository repository) {
        return new PublicLinkLookup(boards, repository);
    }

    @Bean
    PublicIdentifyService publicIdentifyService(
            PublicLinkLookup links,
            VersionedCryptoService crypto,
            PublicIdentifyRateLimiter limiter) {
        return new PublicIdentifyService(links, crypto, limiter);
    }
}
