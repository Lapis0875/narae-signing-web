package com.naraesigning.roster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

final class RosterRowValidator {
    private RosterRowValidator() {}

    static List<RosterIdentity> validate(List<RawRosterRow> rows, List<RosterValidationError> preliminary) {
        var errors = new ArrayList<>(preliminary);
        var identities = new ArrayList<RosterIdentity>();
        var seen = new HashSet<RosterIdentity>();
        for (var row : rows) {
            if (row.row() > RosterService.MAX_ROWS) {
                errors.add(new RosterValidationError(row.row(), "ROW_LIMIT"));
                continue;
            }
            try {
                var identity = new RosterIdentity(row.organization(), row.job(), row.name());
                if (!seen.add(identity)) {
                    errors.add(new RosterValidationError(row.row(), "DUPLICATE_IDENTITY"));
                } else {
                    identities.add(identity);
                }
            } catch (RosterInputException exception) {
                errors.add(new RosterValidationError(row.row(), exception.errors().getFirst().code()));
            }
        }
        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparingInt(RosterValidationError::row));
            throw new RosterInputException(errors);
        }
        return List.copyOf(identities);
    }
}

record RawRosterRow(int row, String organization, String job, String name) {}
