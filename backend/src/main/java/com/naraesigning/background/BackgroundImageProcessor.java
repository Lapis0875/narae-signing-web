package com.naraesigning.background;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;

public final class BackgroundImageProcessor {
    public static final int MAX_UPLOAD_BYTES = 50 * 1024 * 1024;
    public static final int MAX_AXIS = 12_000;
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    public NormalizedBackground normalize(
            byte[] input, String declaredMimeType, CanvasSize currentCanvas, CanvasChange change) {
        if (input == null || input.length > MAX_UPLOAD_BYTES) throw input("FILE_TOO_LARGE");
        var actualMimeType = actualMimeType(input);
        if (!actualMimeType.equals(declaredMimeType)) throw input("MIME_MISMATCH");
        if (change.adoptSourceRatio() && !change.confirmed()) throw input("RATIO_CONFIRMATION_REQUIRED");

        var dimensions = dimensions(input);
        if (dimensions.width() > MAX_AXIS || dimensions.height() > MAX_AXIS) {
            throw input("DIMENSIONS_TOO_LARGE");
        }
        var decoded = decode(input);
        var orientation = actualMimeType.equals("image/jpeg") ? orientation(input) : 1;
        var oriented = orient(decoded, orientation);
        var canvas = change.adoptSourceRatio()
                ? new CanvasSize(oriented.getWidth(), oriented.getHeight())
                : currentCanvas;
        var rendered = contain(oriented, canvas);
        return new NormalizedBackground(encodePng(rendered), canvas,
                oriented.getWidth(), oriented.getHeight());
    }

    private static String actualMimeType(byte[] input) {
        if (input.length >= PNG_MAGIC.length
                && Arrays.equals(Arrays.copyOf(input, PNG_MAGIC.length), PNG_MAGIC)) return "image/png";
        if (input.length >= 3 && input[0] == (byte) 0xff && input[1] == (byte) 0xd8
                && input[2] == (byte) 0xff) return "image/jpeg";
        throw input("UNSUPPORTED_IMAGE");
    }

    private static CanvasSize dimensions(byte[] input) {
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            if (stream == null) throw input("CORRUPT_IMAGE");
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw input("CORRUPT_IMAGE");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                return new CanvasSize(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof BackgroundInputException inputException) throw inputException;
            throw input("CORRUPT_IMAGE");
        }
    }

    private static BufferedImage decode(byte[] input) {
        try {
            var image = ImageIO.read(new ByteArrayInputStream(input));
            if (image == null) throw input("CORRUPT_IMAGE");
            return image;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof BackgroundInputException inputException) throw inputException;
            throw input("CORRUPT_IMAGE");
        }
    }

    private static int orientation(byte[] input) {
        try {
            var metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(input));
            var directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory == null || !directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) return 1;
            var value = directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            return value >= 1 && value <= 8 ? value : 1;
        } catch (Exception exception) {
            return 1;
        }
    }

    private static BufferedImage orient(BufferedImage source, int orientation) {
        var swapped = orientation >= 5;
        var result = new BufferedImage(swapped ? source.getHeight() : source.getWidth(),
                swapped ? source.getWidth() : source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                var point = orientedPoint(x, y, source.getWidth(), source.getHeight(), orientation);
                result.setRGB(point[0], point[1], source.getRGB(x, y));
            }
        }
        return result;
    }

    private static int[] orientedPoint(int x, int y, int width, int height, int orientation) {
        return switch (orientation) {
            case 2 -> new int[] {width - 1 - x, y};
            case 3 -> new int[] {width - 1 - x, height - 1 - y};
            case 4 -> new int[] {x, height - 1 - y};
            case 5 -> new int[] {y, x};
            case 6 -> new int[] {height - 1 - y, x};
            case 7 -> new int[] {height - 1 - y, width - 1 - x};
            case 8 -> new int[] {y, width - 1 - x};
            default -> new int[] {x, y};
        };
    }

    private static BufferedImage contain(BufferedImage source, CanvasSize canvas) {
        var output = new BufferedImage(canvas.width(), canvas.height(), BufferedImage.TYPE_3BYTE_BGR);
        var graphics = output.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, canvas.width(), canvas.height());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            var scale = Math.min((double) canvas.width() / source.getWidth(),
                    (double) canvas.height() / source.getHeight());
            var width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            var height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            graphics.drawImage(source, (canvas.width() - width) / 2, (canvas.height() - height) / 2,
                    width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static byte[] encodePng(BufferedImage image) {
        try {
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) throw input("NORMALIZATION_FAILED");
            return output.toByteArray();
        } catch (IOException exception) {
            throw input("NORMALIZATION_FAILED");
        }
    }

    private static BackgroundInputException input(String code) {
        return new BackgroundInputException(code);
    }
}
