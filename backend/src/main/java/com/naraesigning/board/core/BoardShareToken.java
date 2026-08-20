package com.naraesigning.board.core;

import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.VersionedCryptoService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

final class BoardShareToken {
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern ENCODED = Pattern.compile("[A-Za-z0-9_-]{43}");

    private BoardShareToken() {}

    static Issued issue(UUID boardId, int version, VersionedCryptoService crypto) {
        var random = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(random);
        var rawShare = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        var plaintext = rawShare.getBytes(StandardCharsets.US_ASCII);
        try {
            return new Issued(
                    rawShare,
                    new StoredShare(
                            version,
                            digest(random),
                            crypto.encrypt(plaintext, CryptoContext.shareToken(boardId, version))));
        } finally {
            Arrays.fill(random, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    static Optional<byte[]> parseLookupHash(String rawToken) {
        if (rawToken == null || !ENCODED.matcher(rawToken).matches()) {
            return Optional.empty();
        }
        try {
            var decoded = Base64.getUrlDecoder().decode(rawToken);
            if (decoded.length != TOKEN_BYTES) {
                Arrays.fill(decoded, (byte) 0);
                return Optional.empty();
            }
            try {
                return Optional.of(digest(decoded));
            } finally {
                Arrays.fill(decoded, (byte) 0);
            }
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    static byte[] lookupHash(String rawToken) {
        return parseLookupHash(rawToken).orElseThrow(BoardUnavailableException::new);
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    record Issued(String rawToken, StoredShare stored) {}
}
