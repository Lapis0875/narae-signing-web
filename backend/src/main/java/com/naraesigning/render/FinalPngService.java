package com.naraesigning.render;

import com.naraesigning.background.BackgroundObjectStore;
import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.EncryptedValue;
import com.naraesigning.crypto.VersionedCryptoService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;

final class FinalPngService {
    private static final int BACKGROUND_MAGIC = 0x4e424731;
    private static final int BACKGROUND_HEADER_BYTES = 20;
    private final FinalPngSnapshotRepository snapshots;
    private final BackgroundObjectStore objects;
    private final VersionedCryptoService crypto;
    private final Semaphore permit = new Semaphore(1);

    FinalPngService(FinalPngSnapshotRepository snapshots, BackgroundObjectStore objects,
            VersionedCryptoService crypto) {
        this.snapshots = snapshots;
        this.objects = objects;
        this.crypto = crypto;
    }

    void render(UUID ownerId, UUID boardId, OutputStream output) {
        if (!permit.tryAcquire()) throw FinalPngException.busy();
        try {
            var snapshot = snapshots.readClosed(ownerId, boardId);
            var background = readBackground(snapshot);
            var slots = new ArrayList<FinalPngSlot>();
            var parser = new FinalPngStrokeParser();
            for (var encryptedSlot : snapshot.slots()) {
                var strokes = encryptedSlot.strokes() == null
                        ? java.util.List.<FinalPngStroke>of()
                        : decryptStrokes(encryptedSlot, parser);
                slots.add(new FinalPngSlot(encryptedSlot.x(), encryptedSlot.y(), encryptedSlot.width(),
                        encryptedSlot.height(), encryptedSlot.white(), strokes));
            }
            var width = background == null ? 1920 : snapshot.width();
            var height = background == null ? 1080 : snapshot.height();
            new FinalPngRenderer().render(new FinalPngCanvas(width, height, background, slots), output);
        } catch (FinalPngException exception) {
            throw exception;
        } catch (Exception exception) {
            throw FinalPngException.unavailable();
        } finally {
            permit.release();
        }
    }

    private java.util.List<FinalPngStroke> decryptStrokes(
            FinalPngEncryptedSlot slot, FinalPngStrokeParser parser) {
        var plaintext = crypto.decrypt(slot.strokes(),
                CryptoContext.field("signature-slot", slot.slotId().toString(), "strokes"));
        try {
            return parser.parse(plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private BufferedImage readBackground(FinalPngSnapshot snapshot) throws java.io.IOException {
        if (snapshot.background() == null) return null;
        var background = snapshot.background();
        byte[] objectKey = null;
        byte[] envelope = null;
        byte[] plaintext = null;
        try {
            objectKey = crypto.decrypt(background.encryptedObjectKey(),
                    CryptoContext.field("background-asset", background.assetId().toString(), "object-key"));
            envelope = objects.get(new String(objectKey, StandardCharsets.UTF_8));
            plaintext = decryptEnvelope(envelope, background.assetId());
            var image = ImageIO.read(new ByteArrayInputStream(plaintext));
            if (image == null || image.getWidth() != snapshot.width() || image.getHeight() != snapshot.height()) {
                throw FinalPngException.unavailable();
            }
            return image;
        } finally {
            clear(objectKey);
            clear(envelope);
            clear(plaintext);
        }
    }

    private byte[] decryptEnvelope(byte[] envelope, UUID assetId) {
        if (envelope == null || envelope.length < BACKGROUND_HEADER_BYTES + 16) {
            throw FinalPngException.unavailable();
        }
        var buffer = ByteBuffer.wrap(envelope);
        if (buffer.getInt() != BACKGROUND_MAGIC) throw FinalPngException.unavailable();
        var version = buffer.getInt();
        var nonce = new byte[12];
        buffer.get(nonce);
        var ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        try {
            return crypto.decrypt(new EncryptedValue(ciphertext, nonce, version),
                    CryptoContext.field("background-asset", assetId.toString(), "content"));
        } finally {
            clear(nonce);
            clear(ciphertext);
        }
    }

    private static void clear(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }
}
