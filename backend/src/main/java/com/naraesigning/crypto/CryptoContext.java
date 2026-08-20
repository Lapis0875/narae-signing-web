package com.naraesigning.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public record CryptoContext(String entityType, String entityId, String field) {
    private static final String PREFIX = "narae-signing|v1|";

    public CryptoContext {
        requireComponent(entityType);
        requireComponent(entityId);
        requireComponent(field);
    }

    public static CryptoContext field(String entityType, String entityId, String field) {
        return new CryptoContext(entityType, entityId, field);
    }

    public static CryptoContext shareToken(UUID boardId, int shareLinkVersion) {
        if (shareLinkVersion < 1) {
            throw new IllegalArgumentException("shareLinkVersion must be positive");
        }
        return new CryptoContext("board", Objects.requireNonNull(boardId).toString(),
                "share-token:" + shareLinkVersion);
    }

    byte[] aad() {
        return (PREFIX + entityType + "|" + entityId + "|" + field)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void requireComponent(String component) {
        if (component == null || component.isBlank() || component.indexOf('|') >= 0) {
            throw new IllegalArgumentException("Invalid cryptographic context");
        }
    }
}
