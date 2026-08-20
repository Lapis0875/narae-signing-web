package com.naraesigning.auth;

import com.naraesigning.session.AdminSessionInvalidator;
import com.naraesigning.session.AdminSessionInvalidationPublisher;
import com.naraesigning.session.PathAwareSessionIdResolver;
import com.naraesigning.session.SessionCookieActions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.security.CsrfContractFilter;
import com.naraesigning.security.CsrfTokenContract;
import java.time.Duration;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("spring.datasource.url")
@EnableSpringHttpSession
@EnableScheduling
class AuthConfiguration {
    @Bean
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    LoginAttemptStore loginAttemptStore(JdbcOperations jdbc, Clock authClock) {
        return new LoginAttemptStore(jdbc, authClock);
    }

    @Bean
    AuthService authService(
            JdbcOperations jdbc, LoginAttemptStore attempts, AdminSessionInvalidator sessions) {
        return new AuthService(jdbc, attempts, sessions);
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcIndexedSessionRepository sessionRepository(
            JdbcOperations jdbc, PlatformTransactionManager transactionManager) {
        var repository = new JdbcIndexedSessionRepository(jdbc, new TransactionTemplate(transactionManager));
        repository.setDefaultMaxInactiveInterval(Duration.ofHours(12));
        return repository;
    }

    @Bean
    @ConditionalOnMissingBean
    PathAwareSessionIdResolver httpSessionIdResolver() {
        return new PathAwareSessionIdResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    CsrfTokenContract csrfTokenContract() {
        return new CsrfTokenContract();
    }

    @Bean
    @ConditionalOnMissingBean
    CsrfContractFilter csrfContractFilter(CsrfTokenContract csrf) {
        return new CsrfContractFilter(csrf);
    }

    @Bean
    @ConditionalOnMissingBean
    SessionCookieActions sessionCookieActions(PathAwareSessionIdResolver sessions, CsrfTokenContract csrf) {
        return new SessionCookieActions(sessions, csrf);
    }

    @Bean
    @ConditionalOnMissingBean
    AdminSessionInvalidationPublisher adminSessionInvalidationPublisher(
            JdbcOperations jdbc, ObjectMapper objectMapper) {
        return new AdminSessionInvalidationPublisher(jdbc, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    AdminSessionInvalidator adminSessionInvalidator(
            JdbcIndexedSessionRepository sessions, AdminSessionInvalidationPublisher publisher) {
        return new AdminSessionInvalidator(sessions, publisher);
    }
}
