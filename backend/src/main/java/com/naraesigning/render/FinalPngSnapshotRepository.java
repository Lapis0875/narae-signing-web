package com.naraesigning.render;

import com.naraesigning.crypto.EncryptedValue;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

interface FinalPngSnapshotRepository {
    FinalPngSnapshot readClosed(UUID ownerId, UUID boardId);
}

record FinalPngSnapshot(
        int width,
        int height,
        FinalPngBackground background,
        List<FinalPngEncryptedSlot> slots) {
    FinalPngSnapshot {
        if (width < 1 || height < 1) throw FinalPngException.unavailable();
        slots = List.copyOf(slots);
    }
}

record FinalPngBackground(UUID assetId, EncryptedValue encryptedObjectKey) {
    FinalPngBackground {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(encryptedObjectKey, "encryptedObjectKey");
    }
}

record FinalPngEncryptedSlot(
        UUID slotId,
        BigDecimal x,
        BigDecimal y,
        BigDecimal width,
        BigDecimal height,
        boolean white,
        EncryptedValue strokes) {
    FinalPngEncryptedSlot {
        Objects.requireNonNull(slotId, "slotId");
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(width, "width");
        Objects.requireNonNull(height, "height");
    }
}
