package com.naraesigning.render;

import com.naraesigning.board.core.SignatureInkColor;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import javax.imageio.ImageIO;

final class FinalPngRenderer {
    private static final double COORDINATE_MAX = 1_000_000d;

    void render(FinalPngCanvas canvas, OutputStream output) throws IOException {
        var image = new BufferedImage(canvas.width(), canvas.height(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, canvas.width(), canvas.height());
            if (canvas.background() != null) {
                graphics.drawImage(canvas.background(), 0, 0, canvas.width(), canvas.height(), null);
            }
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(canvas.signatureInkColor() == SignatureInkColor.WHITE ? Color.WHITE : Color.BLACK);
            for (var slot : canvas.slots()) renderSlot(graphics, canvas, slot);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
    }

    private static void renderSlot(java.awt.Graphics2D graphics, FinalPngCanvas canvas, FinalPngSlot slot) {
        var left = edge(slot.x(), canvas.width());
        var top = edge(slot.y(), canvas.height());
        var right = edge(slot.x().add(slot.width()), canvas.width());
        var bottom = edge(slot.y().add(slot.height()), canvas.height());
        var width = Math.max(1, right - left);
        var height = Math.max(1, bottom - top);
        var lineWidth = Math.max(1, (int) Math.floor(Math.min(width, height) * 0.012d + 0.5d));
        graphics.setStroke(new BasicStroke(lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        var inset = Math.min(lineWidth / 2d, Math.min(width, height) / 2d);
        for (var stroke : slot.strokes()) {
            var first = stroke.points().getFirst();
            var path = new Path2D.Double();
            path.moveTo(mapped(first.x(), width, inset) + left, mapped(first.y(), height, inset) + top);
            if (stroke.points().size() == 1) {
                path.lineTo(mapped(first.x(), width, inset) + left + 0.01d,
                        mapped(first.y(), height, inset) + top + 0.01d);
            } else {
                for (var point : stroke.points().subList(1, stroke.points().size())) {
                    path.lineTo(mapped(point.x(), width, inset) + left,
                            mapped(point.y(), height, inset) + top);
                }
            }
            graphics.draw(path);
        }
    }

    private static int edge(BigDecimal normalized, int size) {
        return normalized.multiply(BigDecimal.valueOf(size)).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private static double mapped(int coordinate, int size, double inset) {
        return inset + coordinate / COORDINATE_MAX * (size - inset * 2d);
    }
}

record FinalPngCanvas(
        int width, int height, BufferedImage background, SignatureInkColor signatureInkColor,
        List<FinalPngSlot> slots) {
    FinalPngCanvas { slots = List.copyOf(slots); }
}

record FinalPngSlot(
        BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height,
        List<FinalPngStroke> strokes) {
    FinalPngSlot { strokes = List.copyOf(strokes); }
}

record FinalPngStroke(List<FinalPngPoint> points) {
    FinalPngStroke { points = List.copyOf(points); }
}

record FinalPngPoint(int x, int y) {}
