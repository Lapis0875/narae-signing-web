package com.naraesigning.background;

import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.EncryptedValue;
import com.naraesigning.crypto.VersionedCryptoService;
import java.nio.ByteBuffer;
import java.util.UUID;

final class BackgroundCipherEnvelope {
    private static final int MAGIC = 0x4e424731;
    private static final int HEADER_BYTES = Integer.BYTES * 2 + 12;

    private BackgroundCipherEnvelope() {}

    static byte[] encrypt(byte[] plaintext, VersionedCryptoService crypto, UUID assetId) {
        var value = crypto.encrypt(plaintext, context(assetId));
        return ByteBuffer.allocate(HEADER_BYTES + value.ciphertext().length)
                .putInt(MAGIC).putInt(value.keyVersion()).put(value.nonce()).put(value.ciphertext()).array();
    }

    static byte[] decrypt(byte[] envelope, VersionedCryptoService crypto, UUID assetId) {
        if (envelope == null || envelope.length < HEADER_BYTES + 16) throw new BackgroundStoreException();
        var buffer = ByteBuffer.wrap(envelope);
        if (buffer.getInt() != MAGIC) throw new BackgroundStoreException();
        var version = buffer.getInt();
        var nonce = new byte[12];
        buffer.get(nonce);
        var ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        return crypto.decrypt(new EncryptedValue(ciphertext, nonce, version), context(assetId));
    }

    private static CryptoContext context(UUID assetId) {
        return CryptoContext.field("background-asset", assetId.toString(), "content");
    }
}
