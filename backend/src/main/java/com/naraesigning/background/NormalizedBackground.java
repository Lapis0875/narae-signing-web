package com.naraesigning.background;

final class NormalizedBackground {
    private final byte[] bytes;
    private final CanvasSize canvas;
    private final int sourceWidth;
    private final int sourceHeight;

    NormalizedBackground(byte[] bytes, CanvasSize canvas, int sourceWidth, int sourceHeight) {
        this.bytes = bytes.clone();
        this.canvas = canvas;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
    }

    byte[] bytes() { return bytes.clone(); }
    CanvasSize canvas() { return canvas; }
    int sourceWidth() { return sourceWidth; }
    int sourceHeight() { return sourceHeight; }
    String mimeType() { return "image/png"; }
}
