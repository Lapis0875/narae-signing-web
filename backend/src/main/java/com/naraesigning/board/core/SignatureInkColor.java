package com.naraesigning.board.core;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SignatureInkColor {
    BLACK("black"),
    WHITE("white");

    private final String databaseValue;

    SignatureInkColor(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    @JsonValue
    public String databaseValue() {
        return databaseValue;
    }

    public static SignatureInkColor fromDatabase(String value) {
        return switch (value) {
            case "black" -> BLACK;
            case "white" -> WHITE;
            default -> throw new IllegalArgumentException("Unsupported signature ink color");
        };
    }
}
