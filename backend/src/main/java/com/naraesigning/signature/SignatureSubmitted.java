package com.naraesigning.signature;

import java.time.Instant;
import java.util.UUID;

public record SignatureSubmitted(UUID boardId, UUID slotId, Instant submittedAt) {}
