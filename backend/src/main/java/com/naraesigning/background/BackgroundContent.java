package com.naraesigning.background;

public record BackgroundContent(byte[] bytes, String mimeType) {
    public BackgroundContent {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
