package com.naraesigning.crypto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class VersionedCryptoService {
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] HKDF_SALT =
            "narae-signing|v1|hkdf-salt".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<Integer, DerivedKeys> keyring;
    private final int activeVersion;

    public VersionedCryptoService(Map<Integer, byte[]> masterKeys, int activeVersion) {
        if (masterKeys == null || masterKeys.isEmpty() || !masterKeys.containsKey(activeVersion)) {
            throw new CryptoException();
        }
        var derived = new HashMap<Integer, DerivedKeys>();
        masterKeys.forEach((version, masterKey) -> {
            if (version == null || version < 1 || masterKey == null || masterKey.length != KEY_BYTES) {
                throw new CryptoException();
            }
            var keyCopy = masterKey.clone();
            try {
                derived.put(version, derive(keyCopy, version));
            } finally {
                Arrays.fill(keyCopy, (byte) 0);
            }
        });
        this.keyring = Map.copyOf(derived);
        this.activeVersion = activeVersion;
    }

    public static VersionedCryptoService fromKeyFile(Path keyFile, int activeVersion) {
        try {
            var masterKey = Files.readAllBytes(keyFile);
            try {
                return new VersionedCryptoService(Map.of(activeVersion, masterKey), activeVersion);
            } finally {
                Arrays.fill(masterKey, (byte) 0);
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof CryptoException cryptoException) {
                throw cryptoException;
            }
            throw new CryptoException();
        }
    }

    public EncryptedValue encrypt(byte[] plaintext, CryptoContext context) {
        return encrypt(plaintext, context, activeVersion);
    }

    public EncryptedValue encrypt(byte[] plaintext, CryptoContext context, int keyVersion) {
        var keys = keys(keyVersion);
        var nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.encryptionKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context.aad());
            return new EncryptedValue(cipher.doFinal(plaintext), nonce, keyVersion);
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new CryptoException();
        }
    }

    public byte[] decrypt(EncryptedValue value, CryptoContext context) {
        try {
            var keys = keys(value.keyVersion());
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.encryptionKey(),
                    new GCMParameterSpec(TAG_BITS, value.nonce()));
            cipher.updateAAD(context.aad());
            return cipher.doFinal(value.ciphertext());
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new CryptoException();
        }
    }

    public byte[] identityHmac(String boardId, String organization, String job, String name) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(keys(activeVersion).hmacKey());
            updateLengthPrefixed(mac, boardId);
            updateLengthPrefixed(mac, organization);
            updateLengthPrefixed(mac, job);
            updateLengthPrefixed(mac, name);
            return mac.doFinal();
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new CryptoException();
        }
    }

    private DerivedKeys keys(int version) {
        var keys = keyring.get(version);
        if (keys == null) {
            throw new CryptoException();
        }
        return keys;
    }

    private static DerivedKeys derive(byte[] masterKey, int version) {
        try {
            var extract = Mac.getInstance("HmacSHA256");
            extract.init(new SecretKeySpec(HKDF_SALT, "HmacSHA256"));
            var pseudorandomKey = extract.doFinal(masterKey);
            try {
                return new DerivedKeys(
                        new SecretKeySpec(expand(pseudorandomKey, label(version, "aes-256-gcm")), "AES"),
                        new SecretKeySpec(expand(pseudorandomKey, label(version, "identity-hmac")),
                                "HmacSHA256"));
            } finally {
                Arrays.fill(pseudorandomKey, (byte) 0);
            }
        } catch (GeneralSecurityException exception) {
            throw new CryptoException();
        }
    }

    private static byte[] expand(byte[] pseudorandomKey, byte[] info)
            throws GeneralSecurityException {
        var expand = Mac.getInstance("HmacSHA256");
        expand.init(new SecretKeySpec(pseudorandomKey, "HmacSHA256"));
        expand.update(info);
        expand.update((byte) 1);
        return expand.doFinal();
    }

    private static byte[] label(int version, String purpose) {
        return ("narae-signing|v1|key-version:" + version + "|purpose:" + purpose)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void updateLengthPrefixed(Mac mac, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        mac.update(bytes);
    }

    private record DerivedKeys(SecretKeySpec encryptionKey, SecretKeySpec hmacKey) {}
}
