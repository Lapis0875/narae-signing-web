package com.naraesigning.auth;

import com.naraesigning.admin.AdminEmail;
import com.naraesigning.admin.AdminPassword;
import com.naraesigning.session.AdminSessionInvalidator;
import jakarta.servlet.http.HttpSession;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

class AuthService {
    private static final String INVALID_EMAIL = "invalid-email";
    private final JdbcOperations jdbc;
    private final LoginAttemptStore attempts;
    private final AdminSessionInvalidator sessions;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String dummyHash = passwords.encode("synthetic-dummy-password");

    AuthService(JdbcOperations jdbc, LoginAttemptStore attempts, AdminSessionInvalidator sessions) {
        this.jdbc = jdbc;
        this.attempts = attempts;
        this.sessions = sessions;
    }

    UUID login(String rawEmail, char[] rawPassword, String clientIp) {
        String email = canonicalEmail(rawEmail);
        boolean passwordValid = validPassword(rawPassword);
        return attempts.authenticate(clientIp, email, () -> verify(email, rawPassword, passwordValid));
    }

    @Transactional
    void logout(HttpSession session) {
        Object principal = session.getAttribute(com.naraesigning.session.AdminSessionContract.ADMIN_USER_ID);
        if (principal != null) {
            sessions.logout(session.getId(), UUID.fromString(principal.toString()));
        }
        session.invalidate();
    }

    private UUID verify(String email, char[] rawPassword, boolean passwordValid) {
        Account account = INVALID_EMAIL.equals(email) ? null : jdbc.query(
                "select id, password_hash, status from admin_user where email = ?",
                resultSet -> resultSet.next()
                        ? new Account(
                                resultSet.getObject(1, UUID.class),
                                resultSet.getString(2),
                                resultSet.getString(3))
                        : null,
                email);
        String hash = account == null ? dummyHash : account.passwordHash();
        boolean matched = passwords.matches(CharBuffer.wrap(rawPassword == null ? new char[0] : rawPassword), hash);
        return passwordValid && matched && account != null && "ACTIVE".equals(account.status())
                ? account.id()
                : null;
    }

    private static String canonicalEmail(String rawEmail) {
        try {
            return AdminEmail.parse(rawEmail).value();
        } catch (IllegalArgumentException exception) {
            return INVALID_EMAIL;
        }
    }

    private static boolean validPassword(char[] rawPassword) {
        try {
            AdminPassword parsed = AdminPassword.parse(rawPassword);
            parsed.clear();
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    static void clear(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    private record Account(UUID id, String passwordHash, String status) {}
}
