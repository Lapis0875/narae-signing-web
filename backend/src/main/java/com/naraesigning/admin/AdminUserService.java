package com.naraesigning.admin;

import com.naraesigning.session.AdminSessionInvalidator;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class AdminUserService {
    private final JdbcOperations jdbc;
    private final AdminSessionInvalidator sessions;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

    public AdminUserService(JdbcOperations jdbc, AdminSessionInvalidator sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Transactional
    public UUID create(AdminEmail email, AdminPassword password) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into admin_user (id, email, password_hash, status) values (?, ?, ?, 'ACTIVE')",
                id, email.value(), encode(password));
        return id;
    }

    @Transactional
    public int resetPassword(AdminEmail email, AdminPassword password) {
        UUID id = jdbc.query(
                "select id from admin_user where email = ?",
                resultSet -> resultSet.next() ? resultSet.getObject(1, UUID.class) : null,
                email.value());
        if (id == null) {
            throw new IllegalArgumentException("Administrator does not exist");
        }
        jdbc.update(
                "update admin_user set password_hash = ?, updated_at = current_timestamp where id = ?",
                encode(password), id);
        return sessions.resetAll(id);
    }

    private String encode(AdminPassword password) {
        char[] raw = password.value();
        try {
            return passwords.encode(CharBuffer.wrap(raw));
        } finally {
            Arrays.fill(raw, '\0');
        }
    }
}
