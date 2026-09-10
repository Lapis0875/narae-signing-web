package com.naraesigning.render;

import com.naraesigning.board.core.SignatureInkColor;
import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;

final class FinalPngExclusionTest {
    private static final String IDENTITY_SENTINEL = "IDENTITY_DO_NOT_RENDER_8841";
    private static final String UI_SENTINEL = "UI_TITLE_DO_NOT_RENDER_7712";

    @Test
    void stripsIdentityAndUiSentinelsWithAllSourceMetadata() throws Exception {
        var sourcePng = pngWithSentinelMetadata();
        assertThat(new String(sourcePng, StandardCharsets.ISO_8859_1))
                .contains(IDENTITY_SENTINEL, UI_SENTINEL);
        var background = ImageIO.read(new ByteArrayInputStream(sourcePng));
        var output = new ByteArrayOutputStream();

        new FinalPngRenderer().render(
                new FinalPngCanvas(64, 32, background, SignatureInkColor.BLACK, List.of()), output);

        var rendered = output.toByteArray();
        var image = ImageIO.read(new ByteArrayInputStream(rendered));
        assertThat(pngChunkTypes(rendered)).containsExactly("IHDR", "IDAT", "IEND");
        assertThat(new String(rendered, StandardCharsets.ISO_8859_1))
                .doesNotContain(IDENTITY_SENTINEL, UI_SENTINEL, "tEXt", "iTXt", "zTXt", "eXIf", "tIME");
        for (var y = 0; y < image.getHeight(); y++) {
            for (var x = 0; x < image.getWidth(); x++) {
                assertThat(image.getRGB(x, y)).isEqualTo(Color.WHITE.getRGB());
            }
        }
    }

    private static byte[] pngWithSentinelMetadata() throws Exception {
        var image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        var writer = ImageIO.getImageWritersByFormatName("png").next();
        var metadata = writer.getDefaultImageMetadata(
                javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image), writer.getDefaultWriteParam());
        var root = new IIOMetadataNode("javax_imageio_png_1.0");
        var text = new IIOMetadataNode("tEXt");
        text.appendChild(textEntry("identity", IDENTITY_SENTINEL));
        text.appendChild(textEntry("ui", UI_SENTINEL));
        root.appendChild(text);
        metadata.mergeTree("javax_imageio_png_1.0", root);
        var output = new ByteArrayOutputStream();
        try (var imageOutput = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, metadata), writer.getDefaultWriteParam());
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private static IIOMetadataNode textEntry(String keyword, String value) {
        var entry = new IIOMetadataNode("tEXtEntry");
        entry.setAttribute("keyword", keyword);
        entry.setAttribute("value", value);
        return entry;
    }

    private static List<String> pngChunkTypes(byte[] png) {
        var chunks = new java.util.ArrayList<String>();
        var offset = 8;
        while (offset < png.length) {
            var length = java.nio.ByteBuffer.wrap(png, offset, 4).getInt();
            chunks.add(new String(png, offset + 4, 4, StandardCharsets.US_ASCII));
            offset += 12 + length;
        }
        return List.copyOf(chunks);
    }
}
