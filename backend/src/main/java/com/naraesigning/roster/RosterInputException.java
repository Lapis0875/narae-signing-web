package com.naraesigning.roster;

import java.util.List;

public final class RosterInputException extends RuntimeException {
    static final int MAX_ERRORS = 20;
    private final List<RosterValidationError> errors;

    public RosterInputException(String code) {
        this(List.of(new RosterValidationError(0, code)));
    }

    RosterInputException(List<RosterValidationError> errors) {
        super(errors != null && errors.size() == 1 ? errors.getFirst().code() : "ROSTER_INVALID");
        if (errors == null || errors.isEmpty()) throw new IllegalArgumentException("errors required");
        this.errors = List.copyOf(errors.subList(0, Math.min(errors.size(), MAX_ERRORS)));
    }

    List<RosterValidationError> errors() { return errors; }
}

record RosterValidationError(int row, String code) {
    RosterValidationError {
        if (row < 0 || code == null || code.isBlank()) throw new IllegalArgumentException();
    }
}
