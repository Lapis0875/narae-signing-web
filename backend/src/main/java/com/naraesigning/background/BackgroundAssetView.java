package com.naraesigning.background;

import java.util.UUID;

public record BackgroundAssetView(UUID id, int displayWidth, int displayHeight, String mimeType) {}
