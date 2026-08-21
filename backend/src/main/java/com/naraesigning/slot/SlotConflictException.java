package com.naraesigning.slot;

public final class SlotConflictException extends RuntimeException {
    private final Code code;

    public SlotConflictException(Code code) {
        super(code.name().toLowerCase());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        BOARD_UNAVAILABLE,
        SLOT_NOT_FOUND,
        OVERLAP,
        STALE_SLOT,
        STALE_ASPECT,
        ALREADY_SUBMITTED,
        SIGNATURE_STATE_INVALID
    }
}
