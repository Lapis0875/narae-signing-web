package com.naraesigning.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

final class VersionedCryptoServiceTest {
    private static final byte[] VERSION_ONE_KEY = sequence(0x10);
    private static final byte[] VERSION_TWO_KEY = sequence(0x40);

    @Test
    void supportedVersionsRoundTrip_whenKeyringRetainsOldVersion() {
        // Given: a keyring whose active key is version two and which retains version one.
        var crypto = service();
        var context = CryptoContext.field("roster-entry", "entry-7", "raw-identity");

        // When: values are encrypted with each supported version.
        var oldValue = crypto.encrypt("old".getBytes(StandardCharsets.UTF_8), context, 1);
        var currentValue = crypto.encrypt("current".getBytes(StandardCharsets.UTF_8), context);

        // Then: both key versions decrypt through the same boundary.
        assertThat(crypto.decrypt(oldValue, context)).isEqualTo("old".getBytes(StandardCharsets.UTF_8));
        assertThat(crypto.decrypt(currentValue, context)).isEqualTo("current".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void encryptionUsesFreshNonce_whenPlaintextAndContextAreIdentical() {
        // Given: identical plaintext and authenticated context.
        var crypto = service();
        var plaintext = "same-value".getBytes(StandardCharsets.UTF_8);
        var context = CryptoContext.field("signature-slot", "slot-2", "strokes");

        // When: the plaintext is encrypted twice.
        var first = crypto.encrypt(plaintext, context);
        var second = crypto.encrypt(plaintext, context);

        // Then: both nonce and ciphertext differ.
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    @Test
    void decryptFailsGenerically_whenCiphertextMetadataOrAadIsChanged() {
        // Given: one valid encrypted signature.
        var crypto = service();
        var context = CryptoContext.field("signature-slot", "slot-3", "strokes");
        var value = crypto.encrypt("vector-data".getBytes(StandardCharsets.UTF_8), context);

        // When/Then: every authenticated input change has the same generic boundary failure.
        assertGenericFailure(() -> crypto.decrypt(value.withCiphertext(flipped(value.ciphertext())), context));
        assertGenericFailure(() -> crypto.decrypt(value.withNonce(flipped(value.nonce())), context));
        assertGenericFailure(() -> crypto.decrypt(value.withKeyVersion(1), context));
        assertGenericFailure(() -> crypto.decrypt(value,
                CryptoContext.field("signature-slot", "slot-copied", "strokes")));
    }

    @Test
    void shareTokenAadBindsBoardAndLinkVersion() {
        // Given: a share token encrypted for one board link version.
        var crypto = service();
        var boardId = UUID.fromString("11111111-2222-4333-8444-555555555555");
        var value = crypto.encrypt("share-value".getBytes(StandardCharsets.UTF_8),
                CryptoContext.shareToken(boardId, 4));

        // When/Then: changing either board or link version prevents decryption.
        assertGenericFailure(() -> crypto.decrypt(value,
                CryptoContext.shareToken(UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"), 4)));
        assertGenericFailure(() -> crypto.decrypt(value, CryptoContext.shareToken(boardId, 5)));
    }

    @Test
    void aadUsesExactUtf8Contract_whenFieldContextIsCreated() {
        // Given: an entity-bound field context.
        var context = CryptoContext.field("background-asset", "asset-11", "object-bytes");

        // When: authenticated data is encoded.
        var aad = context.aad();

        // Then: it is the exact versioned UTF-8 contract.
        assertThat(aad).isEqualTo(
                "narae-signing|v1|background-asset|asset-11|object-bytes"
                        .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void identityHmacUsesRawLengthPrefixedFields() {
        // Given: raw roster fields whose concatenations could otherwise be ambiguous.
        var crypto = service();

        // When: exact lookup HMACs are calculated.
        var first = crypto.identityHmac("board", "ab", "c", "d");
        var second = crypto.identityHmac("board", "a", "bc", "d");
        var changedCase = crypto.identityHmac("board", "AB", "c", "d");

        // Then: boundaries and raw case remain significant and the full SHA-256 output is retained.
        assertThat(first).hasSize(32).isNotEqualTo(second).isNotEqualTo(changedCase);
    }

    @Test
    void protectedPayloadsSurviveContextRestart_whenMountedKeyIsUnchanged(@TempDir Path directory)
            throws IOException {
        // Given: a mounted 32-byte key and encrypted roster, signature, and background payloads.
        var keyFile = directory.resolve("master.key");
        Files.write(keyFile, VERSION_TWO_KEY);
        var persisted = new LinkedHashMap<CryptoContext, byte[]>();
        try (var firstContext = context(keyFile)) {
            var crypto = firstContext.getBean(VersionedCryptoService.class);
            persist(persisted, CryptoContext.field("roster-entry", "entry-9", "raw-identity"),
                    crypto, "Acme\u0000Engineer\u0000Kim".getBytes(StandardCharsets.UTF_8));
            persist(persisted, CryptoContext.field("signature-slot", "slot-9", "strokes"),
                    crypto, "[[1,2],[3,4]]".getBytes(StandardCharsets.UTF_8));
            persist(persisted, CryptoContext.field("background-asset", "asset-9", "object-bytes"),
                    crypto, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
        }

        // When: the application context is closed and recreated from the same mounted key.
        try (var restartedContext = context(keyFile)) {
            var crypto = restartedContext.getBean(VersionedCryptoService.class);

            // Then: persisted ciphertext metadata restores all protected values.
            assertThat(crypto.decrypt(read(persisted.get(CryptoContext.field(
                            "roster-entry", "entry-9", "raw-identity"))),
                    CryptoContext.field("roster-entry", "entry-9", "raw-identity")))
                    .isEqualTo("Acme\u0000Engineer\u0000Kim".getBytes(StandardCharsets.UTF_8));
            assertThat(crypto.decrypt(read(persisted.get(CryptoContext.field(
                            "signature-slot", "slot-9", "strokes"))),
                    CryptoContext.field("signature-slot", "slot-9", "strokes")))
                    .isEqualTo("[[1,2],[3,4]]".getBytes(StandardCharsets.UTF_8));
            assertThat(crypto.decrypt(read(persisted.get(CryptoContext.field(
                            "background-asset", "asset-9", "object-bytes"))),
                    CryptoContext.field("background-asset", "asset-9", "object-bytes")))
                    .isEqualTo(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
        }
    }

    @Test
    void keyFileLoadFailsGenerically_whenLengthIsNotExactly32Bytes(@TempDir Path directory)
            throws IOException {
        // Given: malformed mounted keys on both sides of the required size.
        var shortKey = Files.write(directory.resolve("short.key"), new byte[31]);
        var longKey = Files.write(directory.resolve("long.key"), new byte[33]);

        // When/Then: neither malformed value creates a crypto service.
        assertThatThrownBy(() -> VersionedCryptoService.fromKeyFile(shortKey, 1))
                .isInstanceOf(CryptoException.class).hasMessage("Cryptographic operation failed");
        assertThatThrownBy(() -> VersionedCryptoService.fromKeyFile(longKey, 1))
                .isInstanceOf(CryptoException.class).hasMessage("Cryptographic operation failed");
    }

    private static VersionedCryptoService service() {
        return new VersionedCryptoService(Map.of(1, VERSION_ONE_KEY, 2, VERSION_TWO_KEY), 2);
    }

    private static AnnotationConfigApplicationContext context(Path keyFile) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(VersionedCryptoService.class,
                () -> VersionedCryptoService.fromKeyFile(keyFile, 2));
        context.refresh();
        return context;
    }

    private static void persist(Map<CryptoContext, byte[]> target, CryptoContext context,
            VersionedCryptoService crypto, byte[] plaintext) {
        target.put(context, write(crypto.encrypt(plaintext, context)));
    }

    private static byte[] write(EncryptedValue value) {
        return ByteBuffer.allocate(Integer.BYTES * 3 + value.nonce().length + value.ciphertext().length)
                .putInt(value.keyVersion())
                .putInt(value.nonce().length).put(value.nonce())
                .putInt(value.ciphertext().length).put(value.ciphertext())
                .array();
    }

    private static EncryptedValue read(byte[] persisted) {
        var buffer = ByteBuffer.wrap(persisted);
        var version = buffer.getInt();
        var nonce = new byte[buffer.getInt()];
        buffer.get(nonce);
        var ciphertext = new byte[buffer.getInt()];
        buffer.get(ciphertext);
        return new EncryptedValue(ciphertext, nonce, version);
    }

    private static byte[] flipped(byte[] original) {
        var copy = original.clone();
        copy[0] ^= 1;
        return copy;
    }

    private static void assertGenericFailure(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(CryptoException.class)
                .hasMessage("Cryptographic operation failed")
                .hasNoCause();
    }

    private static byte[] sequence(int start) {
        var bytes = new byte[32];
        for (var index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (start + index);
        }
        return bytes;
    }
}
