package com.naraesigning.signature;

import java.util.List;

record SignaturePayload(byte[] canonicalBytes, List<Stroke> strokes) {
    SignaturePayload {
        canonicalBytes = canonicalBytes.clone();
        strokes = List.copyOf(strokes);
    }

    @Override
    public byte[] canonicalBytes() {
        return canonicalBytes.clone();
    }

    record Stroke(List<Point> points) {
        Stroke {
            points = List.copyOf(points);
        }
    }

    record Point(int x, int y) {}
}
