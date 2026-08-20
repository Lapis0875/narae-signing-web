package com.naraesigning.admin;

import java.util.Locale;

public final class AdminEmail {
    private final String value;

    private AdminEmail(String value) {
        this.value = value;
    }

    public static AdminEmail parse(String input) {
        if (input == null || input.length() < 3 || input.length() > 254) {
            throw invalid();
        }

        int separator = input.indexOf('@');
        if (separator < 1 || separator != input.lastIndexOf('@') || separator > 64) {
            throw invalid();
        }
        parseLocal(input, separator);
        parseDomain(input, separator + 1);
        return new AdminEmail(input.toLowerCase(Locale.ROOT));
    }

    public String value() {
        return value;
    }

    private static void parseLocal(String input, int end) {
        boolean atomStart = true;
        for (int index = 0; index < end; index++) {
            char character = input.charAt(index);
            if (character == '.') {
                if (atomStart) {
                    throw invalid();
                }
                atomStart = true;
            } else if (isAtext(character)) {
                atomStart = false;
            } else {
                throw invalid();
            }
        }
        if (atomStart) {
            throw invalid();
        }
    }

    private static void parseDomain(String input, int start) {
        int labelLength = 0;
        char previous = 0;
        for (int index = start; index < input.length(); index++) {
            char character = input.charAt(index);
            if (character == '.') {
                if (labelLength == 0 || previous == '-') {
                    throw invalid();
                }
                labelLength = 0;
            } else if (isAsciiAlphanumeric(character) || (character == '-' && labelLength > 0)) {
                if (++labelLength > 63) {
                    throw invalid();
                }
            } else {
                throw invalid();
            }
            previous = character;
        }
        if (labelLength == 0 || previous == '-') {
            throw invalid();
        }
    }

    private static boolean isAtext(char character) {
        return isAsciiAlphanumeric(character)
                || character == '!'
                || (character >= '#' && character <= '\'')
                || character == '*'
                || character == '+'
                || character == '-'
                || character == '/'
                || character == '='
                || character == '?'
                || (character >= '^' && character <= '`')
                || (character >= '{' && character <= '~');
    }

    private static boolean isAsciiAlphanumeric(char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9');
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid administrator email");
    }
}
