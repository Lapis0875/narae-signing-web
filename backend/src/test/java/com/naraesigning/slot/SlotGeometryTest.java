package com.naraesigning.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SlotGeometryTest {
    @Test
    void acceptsExactNormalizedBoundary() {
        // Given / When
        var bounds = SlotBounds.of(decimal("0"), decimal("0"), decimal("1"), decimal("1"));

        // Then
        assertThat(bounds.right()).isEqualByComparingTo("1");
        assertThat(bounds.bottom()).isEqualByComparingTo("1");
    }

    @Test
    void rejectsCoordinateOutsideNormalizedBoundary() {
        // Given / When / Then
        assertThatThrownBy(() -> SlotBounds.of(decimal("-0.00000001"), decimal("0"), decimal("1"), decimal("1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlotBounds.of(decimal("0"), decimal("1"), decimal("1"), decimal("0.1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlotBounds.of(decimal("0"), decimal("-0.00000001"), decimal("1"), decimal("1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveSizeAndOverflow() {
        // Given / When / Then
        assertThatThrownBy(() -> SlotBounds.of(decimal("0"), decimal("0"), decimal("0"), decimal("1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlotBounds.of(decimal("0.9"), decimal("0"), decimal("0.10000001"), decimal("1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlotBounds.of(decimal("0"), decimal("0"), decimal("1"), decimal("0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlotBounds.of(decimal("0"), decimal("0.9"), decimal("1"), decimal("0.10000001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlotBounds.of(decimal("0"), decimal("0"), decimal("0.000000001"), decimal("1")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void touchingEdgesDoNotOverlap() {
        // Given
        var left = SlotBounds.of(decimal("0"), decimal("0"), decimal("0.5"), decimal("1"));
        var right = SlotBounds.of(decimal("0.5"), decimal("0"), decimal("0.5"), decimal("1"));

        // When / Then
        assertThat(left.overlaps(right)).isFalse();
        var top = SlotBounds.of(decimal("0"), decimal("0"), decimal("1"), decimal("0.5"));
        var bottom = SlotBounds.of(decimal("0"), decimal("0.5"), decimal("1"), decimal("0.5"));
        assertThat(top.overlaps(bottom)).isFalse();
    }

    @Test
    void positiveAreaIntersectionOverlaps() {
        // Given
        var first = SlotBounds.of(decimal("0"), decimal("0"), decimal("0.50000001"), decimal("1"));
        var second = SlotBounds.of(decimal("0.5"), decimal("0"), decimal("0.5"), decimal("1"));

        // When / Then
        assertThat(first.overlaps(second)).isTrue();
    }

    @Test
    void canonicalAspectUsesHalfUpSixDecimalsAndBounds() {
        // Given / When
        var aspect = CanonicalAspect.from(decimal("1"), decimal("6"));

        // Then
        assertThat(aspect.value()).isEqualByComparingTo("0.166667");
        assertThat(CanonicalAspect.of(decimal("1000.0000004")).value()).isEqualByComparingTo("1000.000000");
        assertThatThrownBy(() -> CanonicalAspect.of(decimal("1000.0000005")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalAspect.of(decimal("0.0000004")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
