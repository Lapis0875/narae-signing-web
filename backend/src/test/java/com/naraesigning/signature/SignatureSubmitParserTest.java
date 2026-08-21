package com.naraesigning.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SignatureSubmitParserTest {
    @Test
    void decodesTheExactVersionOneIntegerWireContract() {
        // Given
        var json = """
                {"version":1,"strokes":[{"points":[{"x":0,"y":1000000},{"x":500000,"y":250000}]}]}
                """;

        // When
        var payload = new SignatureWireParser().parse(input(json));

        // Then
        assertThat(new String(payload.canonicalBytes(), StandardCharsets.UTF_8)).isEqualTo(json.strip());
        assertThat(payload.strokes()).hasSize(1);
        assertThat(payload.strokes().getFirst().points()).containsExactly(
                new SignaturePayload.Point(0, 1_000_000),
                new SignaturePayload.Point(500_000, 250_000));
    }

    @Test
    void rejectsNonIntegerAndUnknownWireShapes() {
        // Given / When / Then
        assertThatThrownBy(() -> new SignatureWireParser().parse(input(
                "{\"version\":1,\"strokes\":[{\"points\":[{\"x\":0.5,\"y\":1}]}]}")))
                .isInstanceOf(SignatureSubmitException.class);
        assertThatThrownBy(() -> new SignatureWireParser().parse(input(
                "{\"version\":1,\"strokes\":[],\"extra\":true}")))
                .isInstanceOf(SignatureSubmitException.class);
    }

    @Test
    void rejectsStrokePointAndByteCapsAtTheBoundary() {
        // Given
        var tooManyStrokes = "{\"version\":1,\"strokes\":["
                + "{\"points\":[{\"x\":1,\"y\":1}]},".repeat(SignatureWireParser.MAX_STROKES)
                + "{\"points\":[{\"x\":1,\"y\":1}]}]}";
        var tooManyPoints = "{\"version\":1,\"strokes\":[{\"points\":["
                + "{\"x\":1,\"y\":1},".repeat(SignatureWireParser.MAX_POINTS)
                + "{\"x\":1,\"y\":1}]}]}";
        var oversized = " ".repeat(SignatureWireParser.MAX_PAYLOAD_BYTES + 1);

        // When / Then
        assertThatThrownBy(() -> new SignatureWireParser().parse(input(tooManyStrokes)))
                .isInstanceOf(SignatureSubmitException.class);
        assertThatThrownBy(() -> new SignatureWireParser().parse(input(tooManyPoints)))
                .isInstanceOf(SignatureSubmitException.class);
        assertThatThrownBy(() -> new SignatureWireParser().parse(input(oversized)))
                .isInstanceOf(SignatureSubmitException.class);
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
