package com.naraesigning.slot;

import java.time.Instant;
import java.util.Objects;

public record SlotSignature(byte[] ciphertext, byte[] nonce, int keyVersion, Instant submittedAt) {
    public SlotSignature {
        ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
        nonce = Objects.requireNonNull(nonce, "nonce").clone();
        Objects.requireNonNull(submittedAt, "submittedAt");
        if (ciphertext.length == 0 || nonce.length == 0 || keyVersion < 1) {
            throw new IllegalArgumentException("Invalid encrypted slot signature");
        }
    }

    @Override public byte[] ciphertext() { return ciphertext.clone(); }
    @Override public byte[] nonce() { return nonce.clone(); }
}
