package com.naraesigning.admin;

import com.naraesigning.session.AdminSessionInvalidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(JdbcOperations.class)
class AdminConfiguration {
    @Bean
    AdminUserService adminUserService(JdbcOperations jdbc, AdminSessionInvalidator sessions) {
        return new AdminUserService(jdbc, sessions);
    }

    @Bean
    AdminCommand adminCommand(AdminUserService users) {
        return new AdminCommand(users);
    }
}
