package com.naraesigning.render;

final class FinalPngException extends RuntimeException {
    private final String code;

    private FinalPngException(String code) {
        super(code);
        this.code = code;
    }

    String code() { return code; }

    static FinalPngException busy() { return new FinalPngException("FINAL_PNG_BUSY"); }
    static FinalPngException forbidden() { return new FinalPngException("FINAL_PNG_FORBIDDEN"); }
    static FinalPngException notClosed() { return new FinalPngException("FINAL_PNG_NOT_CLOSED"); }
    static FinalPngException unauthorized() { return new FinalPngException("UNAUTHORIZED"); }
    static FinalPngException unavailable() { return new FinalPngException("FINAL_PNG_UNAVAILABLE"); }
}
