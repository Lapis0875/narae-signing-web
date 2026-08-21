package com.naraesigning.slot;

import java.util.Objects;
import java.util.UUID;

public record Slot(
        UUID boardId,
        UUID id,
        UUID rosterEntryId,
        SlotBounds bounds,
        SlotBackground background,
        long revision,
        boolean submitted,
        boolean signaturePresent) {

    public Slot {
        Objects.requireNonNull(boardId, "boardId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rosterEntryId, "rosterEntryId");
        Objects.requireNonNull(background, "background");
        if (revision < 0) throw new IllegalArgumentException("Slot revision must not be negative");
    }

    public static Slot unplaced(UUID boardId, UUID id, UUID rosterEntryId) {
        return new Slot(boardId, id, rosterEntryId, null, SlotBackground.TRANSPARENT, 0, false, false);
    }

    public boolean placed() {
        return bounds != null;
    }

    public CanonicalAspect aspect(CanvasSize canvas) {
        if (bounds == null) throw new IllegalStateException("Unplaced slot has no aspect");
        Objects.requireNonNull(canvas, "canvas");
        return CanonicalAspect.from(bounds.width().multiply(java.math.BigDecimal.valueOf(canvas.width())),
                bounds.height().multiply(java.math.BigDecimal.valueOf(canvas.height())));
    }

    Slot withVisual(SlotBounds changedBounds, SlotBackground changedBackground) {
        return new Slot(boardId, id, rosterEntryId, Objects.requireNonNull(changedBounds, "bounds"),
                Objects.requireNonNull(changedBackground, "background"), placed() ? revision : nextRevision(),
                submitted, signaturePresent);
    }

    Slot resetSignature() {
        return new Slot(boardId, id, rosterEntryId, bounds, background, nextRevision(), false, false);
    }

    Slot deletePlacement() {
        return new Slot(boardId, id, rosterEntryId, null, SlotBackground.TRANSPARENT,
                nextRevision(), false, false);
    }

    Slot identityEdited() {
        return new Slot(boardId, id, rosterEntryId, bounds, background,
                nextRevision(), submitted, signaturePresent);
    }

    Slot withSubmissionState(boolean changedSubmitted, boolean changedSignaturePresent) {
        return new Slot(boardId, id, rosterEntryId, bounds, background, revision,
                changedSubmitted, changedSignaturePresent);
    }

    private long nextRevision() {
        return Math.addExact(revision, 1);
    }
}
