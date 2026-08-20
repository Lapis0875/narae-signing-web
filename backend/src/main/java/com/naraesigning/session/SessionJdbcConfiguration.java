package com.naraesigning.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.security.CsrfContractFilter;
import com.naraesigning.security.CsrfTokenContract;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(JdbcOperations.class)
@EnableSpringHttpSession
class SessionJdbcConfiguration {
    @Bean
    JdbcIndexedSessionRepository sessionRepository(
            JdbcOperations jdbc, PlatformTransactionManager transactionManager) {
        var repository = new JdbcIndexedSessionRepository(jdbc, new TransactionTemplate(transactionManager));
        repository.setDefaultMaxInactiveInterval(Duration.ofHours(12));
        return repository;
    }

    @Bean
    PathAwareSessionIdResolver httpSessionIdResolver() {
        return new PathAwareSessionIdResolver();
    }

    @Bean
    CsrfTokenContract csrfTokenContract() {
        return new CsrfTokenContract();
    }

    @Bean
    CsrfContractFilter csrfContractFilter(CsrfTokenContract csrf) {
        return new CsrfContractFilter(csrf);
    }

    @Bean
    SessionCookieActions sessionCookieActions(PathAwareSessionIdResolver sessions, CsrfTokenContract csrf) {
        return new SessionCookieActions(sessions, csrf);
    }

    @Bean
    AdminSessionInvalidationPublisher adminSessionInvalidationPublisher(
            JdbcOperations jdbc, ObjectMapper objectMapper) {
        return new AdminSessionInvalidationPublisher(jdbc, objectMapper);
    }

    @Bean
    AdminSessionInvalidator adminSessionInvalidator(
            JdbcIndexedSessionRepository sessions, AdminSessionInvalidationPublisher publisher) {
        return new AdminSessionInvalidator(sessions, publisher);
    }
}
