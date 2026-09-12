package com.naraesigning.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.naraesigning.background.BackgroundObjectStore;
import com.naraesigning.board.core.SignatureInkColor;
import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.EncryptedValue;
import com.naraesigning.crypto.VersionedCryptoService;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class FinalPngServiceTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SLOT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ASSET = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Test
    void rendersWhiteBoardInkOverDarkBackgroundWithoutPaintingSlotRectangle() throws Exception {
        // Given
        var crypto = crypto();
        var background = solidPng(100, 80, new Color(32, 32, 32));
        var objects = new FixedObjects(envelope(crypto, ASSET, background));
        var json = """
                {"version":1,"strokes":[{"points":[{"x":0,"y":500000},{"x":1000000,"y":500000}]}]}
                """.getBytes(StandardCharsets.UTF_8);
        var encrypted = crypto.encrypt(json, CryptoContext.field("signature-slot", SLOT.toString(), "strokes"));
        var snapshot = snapshotWithBackground(crypto, SignatureInkColor.WHITE, 100, 80,
                List.of(new FinalPngEncryptedSlot(SLOT, decimal("0.20000000"), decimal("0.25000000"),
                        decimal("0.60000000"), decimal("0.50000000"), encrypted)));
        var output = new ByteArrayOutputStream();

        // When
        new FinalPngService(new MutableSnapshots(snapshot), objects, crypto).render(OWNER, BOARD, output);

        // Then
        var png = output.toByteArray();
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(100);
        assertThat(image.getHeight()).isEqualTo(80);
        assertThat(image.getRGB(50, 40)).isEqualTo(Color.WHITE.getRGB());
        assertThat(image.getRGB(25, 25)).isEqualTo(new Color(32, 32, 32).getRGB());
        assertThat(image.getRGB(50, 30)).isEqualTo(new Color(32, 32, 32).getRGB());
        var evidence = System.getenv("TASK5_WHITE_PNG_EVIDENCE");
        if (evidence != null) java.nio.file.Files.write(java.nio.file.Path.of(evidence), png);
    }

    @Test
    void rejectsWhiteInkWhenBackgroundMetadataIsAbsentAndPermitRemainsUsable() {
        // Given
        var crypto = crypto();
        var snapshots = new MutableSnapshots(new FinalPngSnapshot(
                100, 80, SignatureInkColor.WHITE, null, List.of()));
        var service = new FinalPngService(snapshots, new MemoryObjects(), crypto);

        // When / Then
        assertThatThrownBy(() -> service.render(OWNER, BOARD, new ByteArrayOutputStream()))
                .isInstanceOfSatisfying(FinalPngException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FINAL_PNG_UNAVAILABLE"));
        snapshots.current = new FinalPngSnapshot(100, 80, SignatureInkColor.BLACK, null, List.of());
        var output = new ByteArrayOutputStream();
        service.render(OWNER, BOARD, output);
        assertThat(output.size()).isPositive();
    }

    @Test
    void rejectsMissingInkMetadata() {
        assertThatThrownBy(() -> new FinalPngSnapshot(100, 80, null, null, List.of()))
                .isInstanceOfSatisfying(FinalPngException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FINAL_PNG_UNAVAILABLE"));
    }

    @Test
    void rejectsMissingCorruptUndecodableAndWrongSizedBackgroundsWithoutLeakingPermit() {
        var crypto = crypto();
        var failures = List.of(
                new BackgroundFailure("missing-object", null),
                new BackgroundFailure("corrupt-envelope", new byte[] {1, 2, 3}),
                new BackgroundFailure("imageio-null", envelope(crypto, ASSET, new byte[] {4, 5, 6})),
                new BackgroundFailure("wrong-dimensions", envelope(crypto, ASSET, solidPng(3, 2, Color.BLACK))));

        for (var failure : failures) {
            var snapshots = new MutableSnapshots(backgroundSnapshot(crypto));
            var service = new FinalPngService(snapshots, new NullableObjects(failure.object()), crypto);
            assertThatThrownBy(() -> service.render(OWNER, BOARD, new ByteArrayOutputStream()))
                    .as(failure.name())
                    .isInstanceOfSatisfying(FinalPngException.class,
                            exception -> assertThat(exception.code()).isEqualTo("FINAL_PNG_UNAVAILABLE"));
            snapshots.current = new FinalPngSnapshot(2, 2, SignatureInkColor.BLACK, null, List.of());
            var recovery = new ByteArrayOutputStream();
            service.render(OWNER, BOARD, recovery);
            assertThat(recovery.size()).as(failure.name()).isPositive();
        }
    }

    @Test
    void decryptsCurrentSnapshotAndDoesNotPersistOrRetainPng() {
        // Given
        var crypto = crypto();
        var snapshots = new MutableSnapshots(snapshot(crypto, 0));
        var objects = new MemoryObjects();
        var service = new FinalPngService(snapshots, objects, crypto);

        // When
        var first = new ByteArrayOutputStream();
        service.render(OWNER, BOARD, first);
        snapshots.current = snapshot(crypto, 1_000_000);
        var second = new ByteArrayOutputStream();
        service.render(OWNER, BOARD, second);

        // Then
        assertThat(first.toByteArray()).isNotEqualTo(second.toByteArray());
        assertThat(snapshots.reads).isEqualTo(2);
        assertThat(objects.writes).isZero();
        assertThat(java.util.Arrays.stream(FinalPngService.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType))
                .noneMatch(type -> type.equals(byte[].class) || type.equals(java.awt.image.BufferedImage.class));
    }

    @Test
    void heldSemaphoreRejectsSecondRequestAndReleasesAfterFailure() throws Exception {
        // Given
        var crypto = crypto();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var objects = new BlockingObjects(started, release);
        var service = new FinalPngService(new MutableSnapshots(backgroundSnapshot(crypto)), objects, crypto);

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.render(OWNER, BOARD, new ByteArrayOutputStream()));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.render(OWNER, BOARD, new ByteArrayOutputStream()))
                    .isInstanceOfSatisfying(FinalPngException.class,
                            exception -> assertThat(exception.code()).isEqualTo("FINAL_PNG_BUSY"));
            release.countDown();
            assertThatThrownBy(first::get).hasCauseInstanceOf(FinalPngException.class);
        }
        objects.fail = false;
        service.render(OWNER, BOARD, new ByteArrayOutputStream());

        // Then
        assertThat(objects.reads).isEqualTo(2);
    }

    @Test
    void corruptStrokeCiphertextFailsWithGenericSafeError() {
        // Given
        var crypto = crypto();
        var corrupt = new EncryptedValue(new byte[16], new byte[12], 7);
        var slot = new FinalPngEncryptedSlot(SLOT, decimal("0.00000000"), decimal("0.00000000"),
                decimal("1.00000000"), decimal("1.00000000"), corrupt);
        var service = new FinalPngService(new MutableSnapshots(
                new FinalPngSnapshot(4, 4, SignatureInkColor.BLACK, null, List.of(slot))),
                new MemoryObjects(), crypto);

        // When / Then
        assertThatThrownBy(() -> service.render(OWNER, BOARD, new ByteArrayOutputStream()))
                .isInstanceOfSatisfying(FinalPngException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FINAL_PNG_UNAVAILABLE"));
    }

    private static FinalPngSnapshot snapshot(VersionedCryptoService crypto, int coordinate) {
        var json = ("{\"version\":1,\"strokes\":[{\"points\":[{\"x\":" + coordinate
                + ",\"y\":" + coordinate + "}]}]}").getBytes(StandardCharsets.UTF_8);
        var encrypted = crypto.encrypt(json, CryptoContext.field("signature-slot", SLOT.toString(), "strokes"));
        return new FinalPngSnapshot(4, 4, SignatureInkColor.BLACK, null, List.of(new FinalPngEncryptedSlot(
                SLOT, decimal("0.00000000"), decimal("0.00000000"), decimal("1.00000000"),
                decimal("1.00000000"), encrypted)));
    }

    private static FinalPngSnapshot backgroundSnapshot(VersionedCryptoService crypto) {
        var key = "background/key".getBytes(StandardCharsets.UTF_8);
        var encryptedKey = crypto.encrypt(key,
                CryptoContext.field("background-asset", ASSET.toString(), "object-key"));
        return new FinalPngSnapshot(2, 2, SignatureInkColor.BLACK,
                new FinalPngBackground(ASSET, encryptedKey), List.of());
    }

    private static FinalPngSnapshot snapshotWithBackground(VersionedCryptoService crypto,
            SignatureInkColor inkColor, int width, int height, List<FinalPngEncryptedSlot> slots) {
        var key = "background/key".getBytes(StandardCharsets.UTF_8);
        var encryptedKey = crypto.encrypt(key,
                CryptoContext.field("background-asset", ASSET.toString(), "object-key"));
        return new FinalPngSnapshot(width, height, inkColor, new FinalPngBackground(ASSET, encryptedKey), slots);
    }

    private static VersionedCryptoService crypto() {
        return new VersionedCryptoService(Map.of(7, new byte[32]), 7);
    }

    private static java.math.BigDecimal decimal(String value) {
        return new java.math.BigDecimal(value);
    }

    private static final class MutableSnapshots implements FinalPngSnapshotRepository {
        private FinalPngSnapshot current;
        private int reads;

        private MutableSnapshots(FinalPngSnapshot current) { this.current = current; }

        @Override public FinalPngSnapshot readClosed(UUID ownerId, UUID boardId) {
            reads++;
            return current;
        }
    }

    private static class MemoryObjects implements BackgroundObjectStore {
        int reads;
        int writes;
        @Override public byte[] get(String objectKey) { reads++; throw new IllegalStateException("missing"); }
        @Override public void put(String objectKey, byte[] ciphertext) { writes++; }
        @Override public void delete(String objectKey) {}
    }

    private static final class BlockingObjects extends MemoryObjects {
        private final CountDownLatch started;
        private final CountDownLatch release;
        private boolean fail = true;

        private BlockingObjects(CountDownLatch started, CountDownLatch release) {
            this.started = started;
            this.release = release;
        }

        @Override public byte[] get(String objectKey) {
            reads++;
            started.countDown();
            try {
                assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            if (fail) throw new IllegalStateException("synthetic corrupt object");
            return envelope(crypto(), ASSET, solidPng(2, 2, Color.BLACK));
        }
    }

    private static final class FixedObjects extends MemoryObjects {
        private final byte[] object;

        private FixedObjects(byte[] object) { this.object = object; }

        @Override public byte[] get(String objectKey) {
            reads++;
            return object.clone();
        }
    }

    private static final class NullableObjects extends MemoryObjects {
        private final byte[] object;

        private NullableObjects(byte[] object) { this.object = object; }

        @Override public byte[] get(String objectKey) {
            reads++;
            return object == null ? null : object.clone();
        }
    }

    private record BackgroundFailure(String name, byte[] object) {}

    private static byte[] envelope(VersionedCryptoService crypto, UUID asset, byte[] png) {
        var encrypted = crypto.encrypt(png,
                CryptoContext.field("background-asset", asset.toString(), "content"));
        return ByteBuffer.allocate(20 + encrypted.ciphertext().length)
                .putInt(0x4e424731).putInt(encrypted.keyVersion())
                .put(encrypted.nonce()).put(encrypted.ciphertext()).array();
    }

    private static byte[] solidPng(int width, int height, Color color) {
        var png = new ByteArrayOutputStream();
        try {
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
            graphics.dispose();
            ImageIO.write(image, "png", png);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
        return png.toByteArray();
    }
}
