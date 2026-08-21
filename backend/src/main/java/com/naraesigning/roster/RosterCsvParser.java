package com.naraesigning.roster;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class RosterCsvParser {
    static final int MAX_BYTES = 1_048_576;
    private static final String HEADER = "소속사,직책,이름";
    private final AtomicInteger entries = new AtomicInteger();

    List<RosterIdentity> parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new RosterInputException("FILE_TOO_LARGE");
        entries.incrementAndGet();
        if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
            throw new RosterInputException("INVALID_ENCODING");
        }
        var text = decode(bytes);
        var lines = text.split("\\r?\\n", -1);
        if (lines.length == 0 || !HEADER.equals(lines[0])) throw new RosterInputException("INVALID_HEADER");
        var rawRows = new ArrayList<RawRosterRow>();
        var errors = new ArrayList<RosterValidationError>();
        int last = lines.length;
        if (last > 1 && lines[last - 1].isEmpty()) last--;
        for (int index = 1; index < last; index++) {
            var fields = lines[index].split(",", -1);
            if (fields.length != 3) {
                errors.add(new RosterValidationError(index, "INVALID_FIELDS"));
            } else {
                rawRows.add(new RawRosterRow(index, fields[0], fields[1], fields[2]));
            }
        }
        return RosterRowValidator.validate(rawRows, errors);
    }

    int entryCount() { return entries.get(); }
    void resetEntryCount() { entries.set(0); }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new RosterInputException("INVALID_ENCODING");
        }
    }
}
