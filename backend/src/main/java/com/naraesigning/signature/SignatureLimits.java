package com.naraesigning.signature;

public final class SignatureLimits {
    public static final int MAX_STROKES = 128;
    public static final int MAX_POINTS = 4_096;
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;
    public static final int MAX_COORDINATE = 1_000_000;

    private SignatureLimits() {}
}
