package com.naraesigning.crypto;

public final class CryptoException extends RuntimeException {
    private static final String MESSAGE = "Cryptographic operation failed";

    CryptoException() {
        super(MESSAGE);
    }
}
