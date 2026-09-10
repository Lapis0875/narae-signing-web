package com.naraesigning.roster;

import java.math.BigDecimal;
import java.util.UUID;

public record RosterEntry(
        UUID id,
        RosterIdentity identity,
        boolean submitted,
        Slot slot) {
    public record Slot(
            UUID id,
            String placementStatus,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            long revision) {}
}
