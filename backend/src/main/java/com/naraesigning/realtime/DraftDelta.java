package com.naraesigning.realtime;

import java.util.List;

public record DraftDelta(
        Operation operation,
        long clientSequence,
        long draftEpoch,
        long revision,
        int strokeIndex,
        List<Point> points) {
    public DraftDelta {
        points = List.copyOf(points);
    }

    public enum Operation {
        BEGIN,
        APPEND,
        END
    }

    public record Point(int x, int y) {}
}
