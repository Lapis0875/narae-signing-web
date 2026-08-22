package com.naraesigning.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.BasicStroke;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class FinalPngGoldenContractTest {
    @Test
    void task18HorizontalVectorHasFourPixelRoundCapAndExactShoulders() throws Exception {
        var stroke = new FinalPngStroke(List.of(
                new FinalPngPoint(0, 500_000), new FinalPngPoint(1_000_000, 500_000)));

        var image = render(300, new FinalPngSlot(zero(), zero(), one(), one(), false, List.of(stroke)));

        // Independent Task 18/24 geometry: width=round(300*0.012)=4, radius=2,
        // endpoints=(2,150)/(298,150). Constants below are not derived by the renderer.
        assertThat(image.getRGB(0, 150)).isEqualTo(new Color(141, 141, 141).getRGB());
        assertThat(image.getRGB(299, 150)).isEqualTo(Color.BLACK.getRGB());
        assertThat(image.getRGB(150, 148)).isEqualTo(new Color(128, 128, 128).getRGB());
        assertThat(image.getRGB(150, 149)).isEqualTo(Color.BLACK.getRGB());
        assertThat(image.getRGB(150, 147)).isEqualTo(Color.WHITE.getRGB());
        assertThat(image.getRGB(1, 148)).isEqualTo(new Color(207, 207, 207).getRGB());
        assertThat(image.getRGB(2, 149)).isEqualTo(Color.BLACK.getRGB());
    }

    @Test
    void task24CornerHasRoundJoinCrownWithoutMiterTip() throws Exception {
        var stroke = new FinalPngStroke(List.of(
                new FinalPngPoint(250_000, 750_000),
                new FinalPngPoint(500_000, 250_000),
                new FinalPngPoint(750_000, 750_000)));

        var image = render(1_000, new FinalPngSlot(zero(), zero(), one(), one(), false, List.of(stroke)));

        // Independent geometry: width=12, radius=6, vertex=(500,253).
        assertThat(image.getRGB(500, 253)).isEqualTo(Color.BLACK.getRGB());
        assertThat(image.getRGB(500, 248)).isEqualTo(Color.BLACK.getRGB());
        assertThat(image.getRGB(500, 245)).isEqualTo(Color.WHITE.getRGB());
        assertThat(image.getRGB(495, 250)).isEqualTo(new Color(91, 91, 91).getRGB());
        assertThat(image.getRGB(492, 246)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void goldenPixelsRejectButtCapBevelJoinMiterJoinAndWrongWidth() {
        assertThat(horizontalMutant(4, BasicStroke.CAP_BUTT).getRGB(0, 150))
                .isNotEqualTo(new Color(141, 141, 141).getRGB());
        assertThat(horizontalMutant(2, BasicStroke.CAP_ROUND).getRGB(150, 148))
                .isNotEqualTo(new Color(128, 128, 128).getRGB());
        assertThat(cornerMutant(BasicStroke.JOIN_BEVEL).getRGB(500, 248))
                .isNotEqualTo(Color.BLACK.getRGB());
        assertThat(cornerMutant(BasicStroke.JOIN_MITER).getRGB(500, 245))
                .isNotEqualTo(Color.WHITE.getRGB());
    }

    private static BufferedImage render(int size, FinalPngSlot slot) throws Exception {
        var output = new ByteArrayOutputStream();
        new FinalPngRenderer().render(new FinalPngCanvas(size, size, null, List.of(slot)), output);
        return ImageIO.read(new ByteArrayInputStream(output.toByteArray()));
    }

    private static BufferedImage horizontalMutant(int width, int cap) {
        return mutant(300, width, cap, BasicStroke.JOIN_ROUND,
                new double[][] {{2, 150}, {298, 150}});
    }

    private static BufferedImage cornerMutant(int join) {
        return mutant(1_000, 12, BasicStroke.CAP_ROUND, join,
                new double[][] {{253, 747}, {500, 253}, {747, 747}});
    }

    private static BufferedImage mutant(int size, int width, int cap, int join, double[][] points) {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, size, size);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(Color.BLACK);
        graphics.setStroke(new BasicStroke(width, cap, join));
        var path = new Path2D.Double();
        path.moveTo(points[0][0], points[0][1]);
        for (var index = 1; index < points.length; index++) path.lineTo(points[index][0], points[index][1]);
        graphics.draw(path);
        graphics.dispose();
        return image;
    }

    private static BigDecimal zero() { return new BigDecimal("0.00000000"); }
    private static BigDecimal one() { return new BigDecimal("1.00000000"); }
}
