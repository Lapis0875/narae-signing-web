package com.naraesigning.slot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record CanonicalAspect(BigDecimal value) {
    public static final int SCALE = 6;
    private static final BigDecimal MAXIMUM = new BigDecimal("1000.000000");

    public CanonicalAspect {
        Objects.requireNonNull(value, "value");
        value = value.setScale(SCALE, RoundingMode.HALF_UP);
        if (value.signum() <= 0 || value.compareTo(MAXIMUM) > 0) {
            throw new IllegalArgumentException("Aspect must be in (0,1000]");
        }
    }

    public static CanonicalAspect of(BigDecimal value) {
        return new CanonicalAspect(value);
    }

    public static CanonicalAspect from(BigDecimal width, BigDecimal height) {
        Objects.requireNonNull(width, "width");
        Objects.requireNonNull(height, "height");
        if (width.signum() <= 0 || height.signum() <= 0) {
            throw new IllegalArgumentException("Aspect dimensions must be positive");
        }
        return of(width.divide(height, SCALE, RoundingMode.HALF_UP));
    }
}
