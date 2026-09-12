package com.naraesigning.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.naraesigning.background.BackgroundObjectStore;
import com.naraesigning.board.core.SignatureInkColor;
import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.VersionedCryptoService;
import com.naraesigning.session.AdminSessionContract;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class FinalPngHttpTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID BOARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SLOT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ASSET = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Test
    void whiteInkDownloadDecodesWithDarkBackgroundAndUnpaintedSlot() throws Exception {
        // Given
        var crypto = new VersionedCryptoService(Map.of(7, new byte[32]), 7);
        var objectKey = "background/key".getBytes(StandardCharsets.UTF_8);
        var encryptedKey = crypto.encrypt(objectKey,
                CryptoContext.field("background-asset", ASSET.toString(), "object-key"));
        var strokeJson = """
                {"version":1,"strokes":[{"points":[{"x":0,"y":500000},{"x":1000000,"y":500000}]}]}
                """.getBytes(StandardCharsets.UTF_8);
        var encryptedStroke = crypto.encrypt(strokeJson,
                CryptoContext.field("signature-slot", SLOT.toString(), "strokes"));
        var snapshot = new FinalPngSnapshot(100, 80, SignatureInkColor.WHITE,
                new FinalPngBackground(ASSET, encryptedKey), List.of(new FinalPngEncryptedSlot(
                        SLOT, decimal("0.20000000"), decimal("0.25000000"), decimal("0.60000000"),
                        decimal("0.50000000"), encryptedStroke)));
        var envelope = envelope(crypto, solidPng(100, 80, new Color(32, 32, 32)));
        var objects = new BackgroundObjectStore() {
            @Override public byte[] get(String key) { return envelope.clone(); }
            @Override public void put(String key, byte[] ciphertext) { throw new AssertionError(); }
            @Override public void delete(String key) { throw new AssertionError(); }
        };

        // When
        var result = mvc((owner, board) -> snapshot, objects, crypto)
                .perform(get(path()).session(session(OWNER, NOW)).secure(true))
                .andExpect(status().isOk())
                .andReturn();

        // Then
        var png = result.getResponse().getContentAsByteArray();
        var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getRGB(50, 40)).isEqualTo(Color.WHITE.getRGB());
        assertThat(image.getRGB(25, 25)).isEqualTo(new Color(32, 32, 32).getRGB());
        assertThat(image.getRGB(50, 30)).isEqualTo(new Color(32, 32, 32).getRGB());
        var evidence = System.getenv("TASK5_HTTP_PNG_EVIDENCE");
        if (evidence != null) java.nio.file.Files.write(java.nio.file.Path.of(evidence), png);
    }

    @Test
    void currentOwnerDownloadsPngWithPrivateHeaders() throws Exception {
        // Given
        var reads = new AtomicInteger();
        var mvc = mvc((owner, board) -> {
            reads.incrementAndGet();
            if (!OWNER.equals(owner)) throw FinalPngException.forbidden();
            return new FinalPngSnapshot(1920, 1080, SignatureInkColor.BLACK, null, List.of());
        });

        // When / Then
        var result = mvc.perform(get(path()).session(session(OWNER, NOW)).secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).startsWith(
                (byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        assertThat(reads).hasValue(1);
    }

    @Test
    void ownerBOpenAndExpiredRequestsFailWithoutProtectedData() throws Exception {
        // Given
        var reads = new AtomicInteger();
        var mvc = mvc((owner, board) -> {
            reads.incrementAndGet();
            if (!OWNER.equals(owner)) throw FinalPngException.forbidden();
            throw FinalPngException.notClosed();
        });

        // When / Then
        mvc.perform(get(path()).session(session(OTHER, NOW)).secure(true))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FINAL_PNG_FORBIDDEN"));
        mvc.perform(get(path()).session(session(OWNER, NOW)).secure(true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FINAL_PNG_NOT_CLOSED"));
        mvc.perform(get(path()).session(session(OWNER,
                        NOW.minus(AdminSessionContract.ABSOLUTE_LIFETIME))).secure(true))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(reads).hasValue(2);
    }

    @Test
    void busyResponseCarriesRetryAfter() throws Exception {
        // Given
        var mvc = mvc((owner, board) -> { throw FinalPngException.busy(); });

        // When / Then
        mvc.perform(get(path()).session(session(OWNER, NOW)).secure(true))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("FINAL_PNG_BUSY"));
    }

    private static MockMvc mvc(FinalPngSnapshotRepository repository) {
        var objects = new BackgroundObjectStore() {
            @Override public byte[] get(String objectKey) { throw new AssertionError(); }
            @Override public void put(String objectKey, byte[] ciphertext) { throw new AssertionError(); }
            @Override public void delete(String objectKey) { throw new AssertionError(); }
        };
        return mvc(repository, objects, new VersionedCryptoService(Map.of(7, new byte[32]), 7));
    }

    private static MockMvc mvc(FinalPngSnapshotRepository repository, BackgroundObjectStore objects,
            VersionedCryptoService crypto) {
        var service = new FinalPngService(repository, objects, crypto);
        return MockMvcBuilders.standaloneSetup(new FinalPngController(service,
                        Clock.fixed(NOW, ZoneOffset.UTC)))
                .setControllerAdvice(new FinalPngControllerAdvice())
                .build();
    }

    private static MockHttpSession session(UUID owner, Instant issuedAt) {
        var session = new MockHttpSession();
        AdminSessionContract.issue(session, owner, issuedAt);
        return session;
    }

    private static String path() {
        return "/api/v1/admin/boards/" + BOARD + "/final.png";
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static byte[] solidPng(int width, int height, Color color) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        var output = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] envelope(VersionedCryptoService crypto, byte[] png) {
        var encrypted = crypto.encrypt(png,
                CryptoContext.field("background-asset", ASSET.toString(), "content"));
        return ByteBuffer.allocate(20 + encrypted.ciphertext().length)
                .putInt(0x4e424731).putInt(encrypted.keyVersion())
                .put(encrypted.nonce()).put(encrypted.ciphertext()).array();
    }
}
