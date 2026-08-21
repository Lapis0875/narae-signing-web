package com.naraesigning.slot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SlotService {
    private final SlotRepository repository;

    SlotService(SlotRepository repository) {
        this.repository = repository;
    }

    public Slot updateVisual(UUID boardId, UUID slotId, SlotBounds bounds, SlotBackground background) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(background, "background");
        return repository.withLockedLayout(boardId, locked -> {
            var changed = required(locked.slots(), slotId).withVisual(bounds, background);
            changed.aspect(locked.canvas());
            validateProspectiveLayout(locked.slots(), changed);
            locked.save(changed, SlotWrite.VISUAL);
            return changed;
        });
    }

    public Slot resetSignature(UUID boardId, UUID slotId) {
        return mutate(boardId, slotId, SlotWrite.RESET, Slot::resetSignature);
    }

    public Slot delete(UUID boardId, UUID slotId) {
        return mutate(boardId, slotId, SlotWrite.DELETE, Slot::deletePlacement);
    }

    public Slot identityEdited(UUID boardId, UUID slotId) {
        return repository.withLockedLayout(boardId, locked -> {
            var current = required(locked.slots(), slotId);
            if (current.submitted() || current.signaturePresent()) {
                throw new SlotConflictException(SlotConflictException.Code.ALREADY_SUBMITTED);
            }
            var changed = current.identityEdited();
            locked.save(changed, SlotWrite.IDENTITY_EDIT);
            return changed;
        });
    }

    public Slot submit(
            UUID boardId,
            UUID slotId,
            long expectedRevision,
            CanonicalAspect expectedAspect,
            SlotSignature signature) {
        Objects.requireNonNull(expectedAspect, "expectedAspect");
        Objects.requireNonNull(signature, "signature");
        return repository.withLockedLayout(boardId, locked -> {
            var current = required(locked.slots(), slotId);
            if (!current.placed()) throw new SlotConflictException(SlotConflictException.Code.STALE_SLOT);
            if (current.revision() != expectedRevision) {
                throw new SlotConflictException(SlotConflictException.Code.STALE_SLOT);
            }
            if (!current.aspect(locked.canvas()).equals(expectedAspect)) {
                throw new SlotConflictException(SlotConflictException.Code.STALE_ASPECT);
            }
            if (current.submitted() != current.signaturePresent()) {
                throw new SlotConflictException(SlotConflictException.Code.SIGNATURE_STATE_INVALID);
            }
            if (current.submitted()) {
                throw new SlotConflictException(SlotConflictException.Code.ALREADY_SUBMITTED);
            }
            var changed = current.withSubmissionState(true, true);
            locked.saveSubmission(changed, signature);
            return changed;
        });
    }

    private Slot mutate(UUID boardId, UUID slotId, SlotWrite write, java.util.function.UnaryOperator<Slot> mutation) {
        return repository.withLockedLayout(boardId, locked -> {
            var changed = mutation.apply(required(locked.slots(), slotId));
            locked.save(changed, write);
            return changed;
        });
    }

    private static Slot required(List<Slot> slots, UUID slotId) {
        return slots.stream().filter(slot -> slot.id().equals(slotId)).findFirst()
                .orElseThrow(() -> new SlotConflictException(SlotConflictException.Code.SLOT_NOT_FOUND));
    }

    private static void validateProspectiveLayout(List<Slot> current, Slot changed) {
        var prospective = new ArrayList<Slot>(current.size());
        current.forEach(slot -> prospective.add(slot.id().equals(changed.id()) ? changed : slot));
        var placed = prospective.stream().filter(Slot::placed).toList();
        for (int first = 0; first < placed.size(); first++) {
            for (int second = first + 1; second < placed.size(); second++) {
                if (placed.get(first).bounds().overlaps(placed.get(second).bounds())) {
                    throw new SlotConflictException(SlotConflictException.Code.OVERLAP);
                }
            }
        }
    }
}
