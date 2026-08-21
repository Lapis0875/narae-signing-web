package com.naraesigning.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class BackgroundImageProcessorTest {
    private final BackgroundImageProcessor processor = new BackgroundImageProcessor();

    @Test
    void normalizesEveryExifOrientationWhenJpegIsValid() throws Exception {
        // Given
        var jpeg = asymmetricJpeg();
        var source = ImageIO.read(new ByteArrayInputStream(jpeg));

        // When / Then
        for (int orientation = 1; orientation <= 8; orientation++) {
            var result = processor.normalize(withExifOrientation(jpeg, orientation), "image/jpeg",
                    new CanvasSize(1, 1), CanvasChange.adoptSourceRatio(true));
            var decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
            var expected = expectedOrientation(source, orientation);
            assertThat(decoded.getWidth()).as("orientation %s width", orientation)
                    .isEqualTo(expected[0].length);
            assertThat(decoded.getHeight()).as("orientation %s height", orientation)
                    .isEqualTo(expected.length);
            for (int y = 0; y < expected.length; y++) {
                for (int x = 0; x < expected[y].length; x++) {
                    assertThat(Math.abs(gray(decoded, x, y) - expected[y][x]))
                            .as("orientation %s pixel (%s,%s)", orientation, x, y)
                            .isLessThanOrEqualTo(2);
                }
            }
        }
    }

    @Test
    void stripsMetadataAndFlattensTransparentPngOntoWhiteCanvas() throws Exception {
        // Given
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, new Color(255, 0, 0, 255).getRGB());
        image.setRGB(1, 0, new Color(0, 0, 255, 0).getRGB());
        var png = withPngText(encode(image, "png"), "Comment", "gate-metadata");
        assertThat(containsAscii(png, "tEXt")).isTrue();
        assertThat(containsAscii(png, "gate-metadata")).isTrue();

        // When
        var result = processor.normalize(png, "image/png", new CanvasSize(2, 2), CanvasChange.keep());

        // Then
        var decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertThat(result.mimeType()).isEqualTo("image/png");
        assertThat(decoded.getType()).isEqualTo(BufferedImage.TYPE_3BYTE_BGR);
        assertThat(new Color(decoded.getRGB(0, 0))).isEqualTo(Color.RED);
        assertThat(new Color(decoded.getRGB(1, 0))).isEqualTo(Color.WHITE);
        assertThat(containsAscii(result.bytes(), "tEXt")).isFalse();
        assertThat(containsAscii(result.bytes(), "gate-metadata")).isFalse();
    }

    @Test
    void adoptsSourceRatioOnlyWithExplicitConfirmation() throws Exception {
        // Given
        var png = encode(new BufferedImage(30, 10, BufferedImage.TYPE_INT_RGB), "png");

        // When / Then
        assertThatThrownBy(() -> processor.normalize(png, "image/png", new CanvasSize(16, 9),
                CanvasChange.adoptSourceRatio(false)))
                .isInstanceOf(BackgroundInputException.class)
                .hasMessage("RATIO_CONFIRMATION_REQUIRED");
        var adopted = processor.normalize(png, "image/png", new CanvasSize(16, 9),
                CanvasChange.adoptSourceRatio(true));
        assertThat(adopted.canvas()).isEqualTo(new CanvasSize(30, 10));
    }

    @Test
    void rejectsOversizeCorruptAndMimeMismatchBeforeDecodeOrStore() throws Exception {
        // Given
        var validPng = encode(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png");
        var boundary = Arrays.copyOf(validPng, BackgroundImageProcessor.MAX_UPLOAD_BYTES);
        var tooLarge = Arrays.copyOf(validPng, BackgroundImageProcessor.MAX_UPLOAD_BYTES + 1);

        // When / Then
        assertThat(processor.normalize(boundary, "image/png", new CanvasSize(1, 1),
                CanvasChange.keep()).sourceWidth()).isEqualTo(1);
        assertRejected(tooLarge, "image/png", "FILE_TOO_LARGE");
        assertRejected(validPng, "image/jpeg", "MIME_MISMATCH");
        assertRejected(new byte[] {1, 2, 3, 4}, "image/png", "UNSUPPORTED_IMAGE");
    }

    @Test
    void requiresCompletePngMagicBeforeDecode() {
        // Given
        var prefixOnly = new byte[] {(byte) 0x89, 'P', 'N', 'G'};
        var completeMagicOnly = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

        // When / Then
        assertRejected(prefixOnly, "image/png", "UNSUPPORTED_IMAGE");
        assertRejected(completeMagicOnly, "image/png", "CORRUPT_IMAGE");
    }

    @Test
    void acceptsAxisBoundaryAndRejectsTheNextPixel() throws Exception {
        // Given
        var boundary = encode(new BufferedImage(BackgroundImageProcessor.MAX_AXIS, 1,
                BufferedImage.TYPE_INT_RGB), "png");
        var over = encode(new BufferedImage(BackgroundImageProcessor.MAX_AXIS + 1, 1,
                BufferedImage.TYPE_INT_RGB), "png");

        // When / Then
        assertThat(processor.normalize(boundary, "image/png", new CanvasSize(1, 1),
                CanvasChange.keep()).sourceWidth()).isEqualTo(BackgroundImageProcessor.MAX_AXIS);
        assertRejected(over, "image/png", "DIMENSIONS_TOO_LARGE");
    }

    private void assertRejected(byte[] bytes, String mimeType, String code) {
        assertThatThrownBy(() -> processor.normalize(bytes, mimeType, new CanvasSize(16, 9),
                CanvasChange.keep())).isInstanceOf(BackgroundInputException.class).hasMessage(code);
    }

    private static byte[] asymmetricJpeg() throws Exception {
        var image = new BufferedImage(3, 2, BufferedImage.TYPE_BYTE_GRAY);
        var values = new int[][] {{20, 60, 100}, {140, 180, 220}};
        for (int y = 0; y < values.length; y++) {
            for (int x = 0; x < values[y].length; x++) image.getRaster().setSample(x, y, 0, values[y][x]);
        }
        return encode(image, "jpeg");
    }

    private static int[][] expectedOrientation(BufferedImage source, int orientation) {
        var a = gray(source, 0, 0);
        var b = gray(source, 1, 0);
        var c = gray(source, 2, 0);
        var d = gray(source, 0, 1);
        var e = gray(source, 1, 1);
        var f = gray(source, 2, 1);
        return switch (orientation) {
            case 1 -> new int[][] {{a, b, c}, {d, e, f}};
            case 2 -> new int[][] {{c, b, a}, {f, e, d}};
            case 3 -> new int[][] {{f, e, d}, {c, b, a}};
            case 4 -> new int[][] {{d, e, f}, {a, b, c}};
            case 5 -> new int[][] {{a, d}, {b, e}, {c, f}};
            case 6 -> new int[][] {{d, a}, {e, b}, {f, c}};
            case 7 -> new int[][] {{f, c}, {e, b}, {d, a}};
            case 8 -> new int[][] {{c, f}, {b, e}, {a, d}};
            default -> throw new IllegalArgumentException();
        };
    }

    private static int gray(BufferedImage image, int x, int y) {
        return new Color(image.getRGB(x, y)).getRed();
    }

    private static byte[] encode(BufferedImage image, String format) throws Exception {
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }

    private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        var tiff = ByteBuffer.allocate(26);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        tiff.putShort((short) 1).putShort((short) 0x0112).putShort((short) 3).putInt(1);
        tiff.putShort((short) orientation).putShort((short) 0).putInt(0);
        var payload = ByteBuffer.allocate(6 + tiff.array().length)
                .put(new byte[] {'E', 'x', 'i', 'f', 0, 0}).put(tiff.array()).array();
        var result = ByteBuffer.allocate(jpeg.length + payload.length + 4);
        result.put(jpeg, 0, 2).put((byte) 0xff).put((byte) 0xe1)
                .putShort((short) (payload.length + 2)).put(payload).put(jpeg, 2, jpeg.length - 2);
        return result.array();
    }

    private static byte[] withPngText(byte[] png, String keyword, String value) {
        var data = (keyword + "\0" + value).getBytes(StandardCharsets.ISO_8859_1);
        var type = "tEXt".getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(type);
        crc.update(data);
        var insertion = ByteBuffer.allocate(Integer.BYTES + type.length + data.length + Integer.BYTES)
                .putInt(data.length).put(type).put(data).putInt((int) crc.getValue()).array();
        var result = ByteBuffer.allocate(png.length + insertion.length);
        result.put(png, 0, png.length - 12).put(insertion).put(png, png.length - 12, 12);
        return result.array();
    }

    private static boolean containsAscii(byte[] bytes, String value) {
        var needle = value.getBytes(StandardCharsets.US_ASCII);
        outer: for (int index = 0; index <= bytes.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (bytes[index + offset] != needle[offset]) continue outer;
            }
            return true;
        }
        return false;
    }
}
