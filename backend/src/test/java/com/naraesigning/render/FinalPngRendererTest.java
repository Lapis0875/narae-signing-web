package com.naraesigning.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class FinalPngRendererTest {
    @Test
    void rendersOpaqueDefaultCanvasWithNormalizedWhiteSlotAndExactWireStroke() throws Exception {
        // Given
        var slot = new FinalPngSlot(
                decimal("0.25000000"), decimal("0.25000000"),
                decimal("0.50000000"), decimal("0.50000000"), true,
                List.of(new FinalPngStroke(List.of(
                        new FinalPngPoint(0, 0), new FinalPngPoint(500_000, 500_000),
                        new FinalPngPoint(1_000_000, 0)))));
        var output = new ByteArrayOutputStream();

        // When
        new FinalPngRenderer().render(new FinalPngCanvas(1920, 1080, null, List.of(slot)), output);

        // Then
        var png = output.toByteArray();
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(1920);
        assertThat(image.getHeight()).isEqualTo(1080);
        assertThat(image.getColorModel().hasAlpha()).isFalse();
        assertThat(image.getRGB(480, 270)).isEqualTo(Color.WHITE.getRGB());
        assertThat(image.getRGB(960, 540)).isEqualTo(Color.BLACK.getRGB());
        assertThat(chunkTypes(png)).containsExactly("IHDR", "IDAT", "IEND");
        var evidence = System.getenv("TASK27_PNG_EVIDENCE");
        if (evidence != null) java.nio.file.Files.write(java.nio.file.Path.of(evidence), png);
    }

    @Test
    void preservesSmallBackgroundDimensionsAndTransparentSlotPixels() throws Exception {
        // Given
        var background = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        var graphics = background.createGraphics();
        graphics.setColor(new Color(12, 34, 56));
        graphics.fillRect(0, 0, 8, 6);
        graphics.dispose();
        var transparent = new FinalPngSlot(decimal("0.00000000"), decimal("0.00000000"),
                decimal("0.50000000"), decimal("1.00000000"), false, List.of());
        var white = new FinalPngSlot(decimal("0.50000000"), decimal("0.00000000"),
                decimal("0.50000000"), decimal("1.00000000"), true, List.of());
        var output = new ByteArrayOutputStream();

        // When
        new FinalPngRenderer().render(new FinalPngCanvas(8, 6, background, List.of(transparent, white)), output);

        // Then
        var image = ImageIO.read(new ByteArrayInputStream(output.toByteArray()));
        assertThat(image.getWidth()).isEqualTo(8);
        assertThat(image.getHeight()).isEqualTo(6);
        assertThat(image.getRGB(1, 3)).isEqualTo(new Color(12, 34, 56).getRGB());
        assertThat(image.getRGB(6, 3)).isEqualTo(Color.WHITE.getRGB());
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static List<String> chunkTypes(byte[] png) {
        var chunks = new java.util.ArrayList<String>();
        var offset = 8;
        while (offset < png.length) {
            var length = java.nio.ByteBuffer.wrap(png, offset, 4).getInt();
            chunks.add(new String(png, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII));
            offset += 12 + length;
        }
        return chunks;
    }
}
