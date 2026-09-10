package com.naraesigning.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.board.core.SignatureInkColor;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BoardSnapshotVersionTest {
    @Test
    void snapshotVersionIsNewerOnlyByLexicographicEpochThenRevision() {
        // Given
        var slot = slot(4, 7);

        // When / Then
        assertThat(slot.isNewerThan(3, 99)).isTrue();
        assertThat(slot.isNewerThan(4, 6)).isTrue();
        assertThat(slot.isNewerThan(4, 7)).isFalse();
        assertThat(slot.isNewerThan(4, 8)).isFalse();
        assertThat(slot.isNewerThan(5, 0)).isFalse();
    }

    @Test
    void snapshotCarriesEpochAndRevisionForClientVersionComparison() {
        // Given
        var slot = slot(4, 7);
        var snapshot = new BoardSnapshot(
                UUID.randomUUID(), 100, 100, false, SignatureInkColor.BLACK, List.of(slot));

        // When
        var json = new ObjectMapper().valueToTree(snapshot).get("slots").get(0);
        var snapshotJson = new ObjectMapper().valueToTree(snapshot);

        // Then
        assertThat(json.get("draftEpoch").longValue()).isEqualTo(4);
        assertThat(json.get("revision").longValue()).isEqualTo(7);
        assertThat(json.has("background")).isFalse();
        assertThat(snapshotJson.get("signatureInkColor").textValue()).isEqualTo("black");
    }

    private static BoardSnapshot.Slot slot(long epoch, long revision) {
        return new BoardSnapshot.Slot(
                UUID.randomUUID(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE,
                null, null, epoch, revision);
    }
}
