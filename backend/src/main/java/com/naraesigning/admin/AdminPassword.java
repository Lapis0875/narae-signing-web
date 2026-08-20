package com.naraesigning.admin;

import java.util.Arrays;

public final class AdminPassword {
    private final char[] value;

    private AdminPassword(char[] value) {
        this.value = value;
    }

    public static AdminPassword parse(char[] input) {
        if (input == null) {
            throw new IllegalArgumentException("Administrator password must have at least 12 characters");
        }
        int codePoints = 0;
        int utf8Bytes = 0;
        for (int index = 0; index < input.length; index++) {
            char character = input[index];
            codePoints++;
            if (character <= 0x7f) {
                utf8Bytes++;
            } else if (character <= 0x7ff) {
                utf8Bytes += 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < input.length
                    && Character.isLowSurrogate(input[index + 1])) {
                utf8Bytes += 4;
                index++;
            } else if (Character.isSurrogate(character)) {
                throw new IllegalArgumentException("Administrator password is not valid Unicode");
            } else {
                utf8Bytes += 3;
            }
        }
        if (codePoints < 12) {
            throw new IllegalArgumentException("Administrator password must have at least 12 characters");
        }
        if (utf8Bytes > 72) {
            throw new IllegalArgumentException("Administrator password exceeds 72 UTF-8 bytes");
        }
        return new AdminPassword(input.clone());
    }

    public char[] value() {
        return value.clone();
    }

    public void clear() {
        Arrays.fill(value, '\0');
    }
}
