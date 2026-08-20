package com.naraesigning.crypto;

public final class EncryptedValue {
    private final byte[] ciphertext;
    private final byte[] nonce;
    private final int keyVersion;

    public EncryptedValue(byte[] ciphertext, byte[] nonce, int keyVersion) {
        if (ciphertext == null || ciphertext.length < 16 || nonce == null || nonce.length != 12
                || keyVersion < 1) {
            throw new CryptoException();
        }
        this.ciphertext = ciphertext.clone();
        this.nonce = nonce.clone();
        this.keyVersion = keyVersion;
    }

    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    public byte[] nonce() {
        return nonce.clone();
    }

    public int keyVersion() {
        return keyVersion;
    }

    public EncryptedValue withCiphertext(byte[] changedCiphertext) {
        return new EncryptedValue(changedCiphertext, nonce, keyVersion);
    }

    public EncryptedValue withNonce(byte[] changedNonce) {
        return new EncryptedValue(ciphertext, changedNonce, keyVersion);
    }

    public EncryptedValue withKeyVersion(int changedKeyVersion) {
        return new EncryptedValue(ciphertext, nonce, changedKeyVersion);
    }
}
