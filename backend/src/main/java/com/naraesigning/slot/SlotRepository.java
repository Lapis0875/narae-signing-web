package com.naraesigning.slot;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

interface SlotRepository {
    <T> T withLockedLayout(UUID boardId, Function<LockedLayout, T> action);

    interface LockedLayout {
        CanvasSize canvas();
        List<Slot> slots();
        void save(Slot slot, SlotWrite write);
        void saveSubmission(Slot slot, SlotSignature signature);
    }
}
