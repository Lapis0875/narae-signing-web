package com.naraesigning.slot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record SlotBounds(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {
    private static final int SCALE = 8;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    public SlotBounds {
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(width, "width");
        Objects.requireNonNull(height, "height");
        if (x.compareTo(ZERO) < 0 || y.compareTo(ZERO) < 0
                || width.compareTo(ZERO) <= 0 || height.compareTo(ZERO) <= 0
                || x.add(width).compareTo(ONE) > 0 || y.add(height).compareTo(ONE) > 0) {
            throw new IllegalArgumentException("Slot bounds must be inside the normalized canvas");
        }
        width = canonical(width);
        height = canonical(height);
        if (width.compareTo(ZERO) <= 0 || height.compareTo(ZERO) <= 0) {
            throw new IllegalArgumentException("Slot bounds must be inside the normalized canvas");
        }
        x = canonical(x).min(ONE.subtract(width));
        y = canonical(y).min(ONE.subtract(height));
    }

    public static SlotBounds of(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {
        return new SlotBounds(x, y, width, height);
    }

    private static BigDecimal canonical(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal right() {
        return x.add(width);
    }

    public BigDecimal bottom() {
        return y.add(height);
    }

    public boolean overlaps(SlotBounds other) {
        Objects.requireNonNull(other, "other");
        return x.compareTo(other.right()) < 0 && right().compareTo(other.x) > 0
                && y.compareTo(other.bottom()) < 0 && bottom().compareTo(other.y) > 0;
    }
}
