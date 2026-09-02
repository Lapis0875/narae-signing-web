package com.naraesigning.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

record BoardSnapshot(UUID boardId, int canvasWidth, int canvasHeight, boolean backgroundPresent,
        List<Slot> slots) {
    record Slot(UUID id, BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height,
            String background, JsonNode signature, JsonNode draftSignature) {}
}
