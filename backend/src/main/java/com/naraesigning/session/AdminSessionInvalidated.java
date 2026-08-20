package com.naraesigning.session;

import java.util.UUID;

public record AdminSessionInvalidated(String sessionId, UUID adminUserId, Reason reason) {
    public enum Reason {
        LOGOUT,
        PASSWORD_RESET
    }
}
