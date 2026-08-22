package com.naraesigning.render;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class FinalPngStrokeParser {
    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_COORDINATE = 1_000_000;
    private static final int MAX_STROKES = 128;
    private static final int MAX_POINTS = 4_096;
    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_BYTES).maxNameLength(16).maxNestingDepth(5)
                    .maxNumberLength(7).maxStringLength(1).build())
            .build();

    List<FinalPngStroke> parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw FinalPngException.unavailable();
        try (var parser = json.createParser(new ByteArrayInputStream(bytes))) {
            require(parser.nextToken(), JsonToken.START_OBJECT);
            requireField(parser, "version");
            require(parser.nextToken(), JsonToken.VALUE_NUMBER_INT);
            if (parser.getIntValue() != 1) throw FinalPngException.unavailable();
            requireField(parser, "strokes");
            require(parser.nextToken(), JsonToken.START_ARRAY);
            var strokes = new ArrayList<FinalPngStroke>();
            var pointCount = 0;
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (strokes.size() == MAX_STROKES) throw FinalPngException.unavailable();
                require(parser.currentToken(), JsonToken.START_OBJECT);
                requireField(parser, "points");
                require(parser.nextToken(), JsonToken.START_ARRAY);
                var points = new ArrayList<FinalPngPoint>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (pointCount == MAX_POINTS) throw FinalPngException.unavailable();
                    points.add(point(parser));
                    pointCount++;
                }
                if (points.isEmpty()) throw FinalPngException.unavailable();
                require(parser.nextToken(), JsonToken.END_OBJECT);
                strokes.add(new FinalPngStroke(points));
            }
            require(parser.nextToken(), JsonToken.END_OBJECT);
            if (parser.nextToken() != null) throw FinalPngException.unavailable();
            return List.copyOf(strokes);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof FinalPngException finalPngException) throw finalPngException;
            throw FinalPngException.unavailable();
        }
    }

    private static FinalPngPoint point(com.fasterxml.jackson.core.JsonParser parser) throws IOException {
        require(parser.currentToken(), JsonToken.START_OBJECT);
        requireField(parser, "x");
        var x = coordinate(parser);
        requireField(parser, "y");
        var y = coordinate(parser);
        require(parser.nextToken(), JsonToken.END_OBJECT);
        return new FinalPngPoint(x, y);
    }

    private static int coordinate(com.fasterxml.jackson.core.JsonParser parser) throws IOException {
        require(parser.nextToken(), JsonToken.VALUE_NUMBER_INT);
        var coordinate = parser.getIntValue();
        if (coordinate < 0 || coordinate > MAX_COORDINATE) throw FinalPngException.unavailable();
        return coordinate;
    }

    private static void requireField(com.fasterxml.jackson.core.JsonParser parser, String name) throws IOException {
        require(parser.nextToken(), JsonToken.FIELD_NAME);
        if (!name.equals(parser.currentName())) throw FinalPngException.unavailable();
    }

    private static void require(JsonToken actual, JsonToken expected) {
        if (actual != expected) throw FinalPngException.unavailable();
    }
}
