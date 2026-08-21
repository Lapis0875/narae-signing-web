package com.naraesigning.roster;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;

public record RosterIdentity(String organization, String job, String name) {
    public static final int MAX_FIELD_CODE_POINTS = 200;

    public RosterIdentity {
        if (organization == null || job == null || name == null) {
            throw new RosterInputException("INVALID_FIELDS");
        }
        if (name.isBlank()) {
            throw new RosterInputException("BLANK_NAME");
        }
        requireBounded(organization);
        requireBounded(job);
        requireBounded(name);
    }

    private static void requireBounded(String value) {
        if (value.codePointCount(0, value.length()) > MAX_FIELD_CODE_POINTS) {
            throw new RosterInputException("FIELD_TOO_LONG");
        }
        try {
            StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new RosterInputException("INVALID_ENCODING");
        }
    }

    byte[] encode() {
        var organizationBytes = organization.getBytes(StandardCharsets.UTF_8);
        var jobBytes = job.getBytes(StandardCharsets.UTF_8);
        var nameBytes = name.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(12 + organizationBytes.length + jobBytes.length + nameBytes.length)
                .putInt(organizationBytes.length).put(organizationBytes)
                .putInt(jobBytes.length).put(jobBytes)
                .putInt(nameBytes.length).put(nameBytes)
                .array();
    }

    static RosterIdentity decode(byte[] bytes) {
        try {
            var buffer = ByteBuffer.wrap(bytes);
            return new RosterIdentity(read(buffer), read(buffer), readLast(buffer));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid encrypted roster identity", exception);
        }
    }

    private static String read(ByteBuffer buffer) {
        int size = buffer.getInt();
        if (size < 0 || size > buffer.remaining()) throw new IllegalStateException();
        var value = new byte[size];
        buffer.get(value);
        return new String(value, StandardCharsets.UTF_8);
    }

    private static String readLast(ByteBuffer buffer) {
        var value = read(buffer);
        if (buffer.hasRemaining()) throw new IllegalStateException();
        return value;
    }
}
