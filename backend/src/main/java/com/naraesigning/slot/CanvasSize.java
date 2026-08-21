package com.naraesigning.slot;

public record CanvasSize(int width, int height) {
    public CanvasSize {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Canvas dimensions must be positive");
    }
}
