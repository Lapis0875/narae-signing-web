package com.naraesigning.roster;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class RosterJsonParser {
    private final ObjectMapper mapper;
    private final AtomicInteger entries = new AtomicInteger();

    RosterJsonParser(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    List<RosterIdentity> parse(byte[] bytes) {
        if (bytes.length > RosterCsvParser.MAX_BYTES) throw new RosterInputException("FILE_TOO_LARGE");
        entries.incrementAndGet();
        try {
            var root = mapper.readTree(bytes);
            if (!root.isObject() || !fields(root).equals(java.util.Set.of("rows")) || !root.get("rows").isArray()) {
                throw new RosterInputException("INVALID_FIELDS");
            }
            var rawRows = new ArrayList<RawRosterRow>();
            var errors = new ArrayList<RosterValidationError>();
            int row = 0;
            for (var node : root.get("rows")) {
                row++;
                if (!node.isObject() || !fields(node).equals(java.util.Set.of("organization", "job", "name"))
                        || !node.get("organization").isTextual() || !node.get("job").isTextual()
                        || !node.get("name").isTextual()) {
                    errors.add(new RosterValidationError(row, "INVALID_FIELDS"));
                } else {
                    rawRows.add(new RawRosterRow(row, node.get("organization").textValue(),
                            node.get("job").textValue(), node.get("name").textValue()));
                }
            }
            return RosterRowValidator.validate(rawRows, errors);
        } catch (RosterInputException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new RosterInputException("INVALID_JSON");
        }
    }

    RosterIdentity parseIdentity(byte[] bytes) {
        if (bytes.length > RosterCsvParser.MAX_BYTES) throw new RosterInputException("FILE_TOO_LARGE");
        try {
            var node = mapper.readTree(bytes);
            if (!node.isObject() || !fields(node).equals(java.util.Set.of("organization", "job", "name"))
                    || !node.get("organization").isTextual() || !node.get("job").isTextual()
                    || !node.get("name").isTextual()) {
                throw new RosterInputException("INVALID_FIELDS");
            }
            return new RosterIdentity(node.get("organization").textValue(),
                    node.get("job").textValue(), node.get("name").textValue());
        } catch (RosterInputException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new RosterInputException("INVALID_JSON");
        }
    }

    int entryCount() { return entries.get(); }
    void resetEntryCount() { entries.set(0); }

    private static java.util.Set<String> fields(com.fasterxml.jackson.databind.JsonNode node) {
        var fields = new HashSet<String>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }
}
