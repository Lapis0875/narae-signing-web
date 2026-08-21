package com.naraesigning.signature;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

final class SignatureWireParser {
    static final int MAX_STROKES = 128;
    static final int MAX_POINTS = 4_096;
    static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private static final int MAX_COORDINATE = 1_000_000;
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_PAYLOAD_BYTES)
                    .maxNameLength(16)
                    .maxNestingDepth(5)
                    .maxNumberLength(7)
                    .maxStringLength(1)
                    .build())
            .build();

    SignaturePayload parse(InputStream input) {
        try (var parser = json.createParser(new LimitedInputStream(input))) {
            require(parser.nextToken(), JsonToken.START_OBJECT);
            requireField(parser, "version");
            requireInteger(parser, 1, 1);
            requireField(parser, "strokes");
            require(parser.nextToken(), JsonToken.START_ARRAY);
            var strokes = new ArrayList<SignaturePayload.Stroke>();
            var pointCount = 0;
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (strokes.size() == MAX_STROKES) throw SignatureSubmitException.invalidPayload();
                require(parser.currentToken(), JsonToken.START_OBJECT);
                requireField(parser, "points");
                require(parser.nextToken(), JsonToken.START_ARRAY);
                var points = new ArrayList<SignaturePayload.Point>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (pointCount == MAX_POINTS) throw SignatureSubmitException.invalidPayload();
                    points.add(parsePoint(parser));
                    pointCount++;
                }
                if (points.isEmpty()) throw SignatureSubmitException.invalidPayload();
                require(parser.nextToken(), JsonToken.END_OBJECT);
                strokes.add(new SignaturePayload.Stroke(points));
            }
            require(parser.nextToken(), JsonToken.END_OBJECT);
            if (parser.nextToken() != null) throw SignatureSubmitException.invalidPayload();
            return new SignaturePayload(canonical(strokes), strokes);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof SignatureSubmitException submitException) {
                throw submitException;
            }
            throw SignatureSubmitException.invalidPayload();
        }
    }

    private static SignaturePayload.Point parsePoint(JsonParser parser) throws IOException {
        require(parser.currentToken(), JsonToken.START_OBJECT);
        requireField(parser, "x");
        var x = coordinate(parser);
        requireField(parser, "y");
        var y = coordinate(parser);
        require(parser.nextToken(), JsonToken.END_OBJECT);
        return new SignaturePayload.Point(x, y);
    }

    private static int coordinate(JsonParser parser) throws IOException {
        require(parser.nextToken(), JsonToken.VALUE_NUMBER_INT);
        var value = parser.getIntValue();
        if (value < 0 || value > MAX_COORDINATE) throw SignatureSubmitException.invalidPayload();
        return value;
    }

    private static void requireField(JsonParser parser, String field) throws IOException {
        require(parser.nextToken(), JsonToken.FIELD_NAME);
        if (!field.equals(parser.currentName())) throw SignatureSubmitException.invalidPayload();
    }

    private static void requireInteger(JsonParser parser, int minimum, int maximum) throws IOException {
        require(parser.nextToken(), JsonToken.VALUE_NUMBER_INT);
        var value = parser.getIntValue();
        if (value < minimum || value > maximum) throw SignatureSubmitException.invalidPayload();
    }

    private static void require(JsonToken actual, JsonToken expected) {
        if (actual != expected) throw SignatureSubmitException.invalidPayload();
    }

    private static byte[] canonical(ArrayList<SignaturePayload.Stroke> strokes) {
        var value = new StringBuilder("{\"version\":1,\"strokes\":[");
        for (var strokeIndex = 0; strokeIndex < strokes.size(); strokeIndex++) {
            if (strokeIndex > 0) value.append(',');
            value.append("{\"points\":[");
            var points = strokes.get(strokeIndex).points();
            for (var pointIndex = 0; pointIndex < points.size(); pointIndex++) {
                if (pointIndex > 0) value.append(',');
                var point = points.get(pointIndex);
                value.append("{\"x\":").append(point.x())
                        .append(",\"y\":").append(point.y()).append('}');
            }
            value.append("]}");
        }
        return value.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private int remaining = MAX_PAYLOAD_BYTES;

        private LimitedInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) return overflowOrEnd();
            var value = super.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (remaining == 0) return overflowOrEnd();
            var count = super.read(target, offset, Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }

        private int overflowOrEnd() throws IOException {
            if (super.read() < 0) return -1;
            throw new IOException("signature payload too large");
        }
    }
}
