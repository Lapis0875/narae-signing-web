package com.naraesigning.background;

public record CanvasChange(boolean adoptSourceRatio, boolean confirmed) {
    public static CanvasChange keep() {
        return new CanvasChange(false, false);
    }

    public static CanvasChange adoptSourceRatio(boolean confirmed) {
        return new CanvasChange(true, confirmed);
    }
}
