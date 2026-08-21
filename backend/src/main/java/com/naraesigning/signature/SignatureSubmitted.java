package com.naraesigning.signature;

import java.time.Instant;
import java.util.UUID;

record SignatureSubmitted(UUID boardId, UUID slotId, Instant submittedAt) {}
