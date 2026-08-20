package com.naraesigning.board.core;

import com.naraesigning.session.AdminSessionContract;
import jakarta.servlet.http.HttpSession;
import java.util.Objects;
import java.util.UUID;

public final class BoardOwner {
    private final UUID id;

    private BoardOwner(UUID id) {
        this.id = Objects.requireNonNull(id);
    }

    public static BoardOwner fromSession(HttpSession session) {
        if (session == null) {
            throw new BoardUnavailableException();
        }
        var principal = session.getAttribute(AdminSessionContract.ADMIN_USER_ID);
        try {
            return new BoardOwner(UUID.fromString(principal == null ? "" : principal.toString()));
        } catch (IllegalArgumentException exception) {
            throw new BoardUnavailableException();
        }
    }

    static BoardOwner synthetic(UUID id) {
        return new BoardOwner(id);
    }

    UUID id() {
        return id;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BoardOwner owner && id.equals(owner.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "BoardOwner[REDACTED]";
    }
}
