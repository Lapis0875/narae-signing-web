package com.naraesigning.slot;

public enum SlotBackground {
    TRANSPARENT("transparent"),
    WHITE("white");

    private final String databaseValue;

    SlotBackground(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public static SlotBackground fromDatabase(String value) {
        return "white".equalsIgnoreCase(value) ? WHITE : TRANSPARENT;
    }
}
