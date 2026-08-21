package com.naraesigning.slot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

final class InMemorySlotRepository implements SlotRepository {
    private final Object lock = new Object();
    private final Map<UUID, Slot> records = new LinkedHashMap<>();
    private final List<String> trace = new ArrayList<>();
    private final int canvasWidth;
    private final int canvasHeight;
    private CountDownLatch entrants;
    private boolean failNextWrite;
    private int writes;

    InMemorySlotRepository(List<Slot> slots, int canvasWidth, int canvasHeight) {
        slots.forEach(slot -> records.put(slot.id(), slot));
        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
    }

    @Override
    public <T> T withLockedLayout(UUID boardId, Function<LockedLayout, T> action) {
        var currentEntrants = entrants;
        if (currentEntrants != null) {
            currentEntrants.countDown();
            await(currentEntrants);
        }
        synchronized (lock) {
            var before = snapshot();
            trace.clear();
            trace.add("board:" + boardId);
            var ordered = records.values().stream().filter(slot -> slot.boardId().equals(boardId))
                    .sorted(Comparator.comparing(slot -> slot.id().toString())).toList();
            ordered.forEach(slot -> trace.add("slot:" + slot.id()));
            try {
                return action.apply(new Locked(ordered));
            } catch (RuntimeException failure) {
                records.clear();
                records.putAll(before);
                throw failure;
            }
        }
    }

    List<UUID> slotIds() { return records.keySet().stream().toList(); }
    List<Slot> slots() { return List.copyOf(records.values()); }
    Slot required(UUID id) { return records.get(id); }
    Map<UUID, Slot> snapshot() { return new LinkedHashMap<>(records); }
    List<String> trace() { return List.copyOf(trace); }
    List<UUID> lockedSlotIds() {
        return trace.stream().filter(value -> value.startsWith("slot:"))
                .map(value -> UUID.fromString(value.substring(5))).toList();
    }
    void failNextWriteAfterMutation() { failNextWrite = true; }
    int writeCount() { return writes; }
    void awaitConcurrentEntrants(int count) { entrants = new CountDownLatch(count); }
    void seedSubmitted(UUID id) {
        records.computeIfPresent(id, (ignored, slot) -> slot.withSubmissionState(true, true));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("barrier interrupted", exception);
        }
    }

    private final class Locked implements LockedLayout {
        private final List<Slot> slots;

        private Locked(List<Slot> slots) {
            this.slots = slots;
        }

        @Override public CanvasSize canvas() { return new CanvasSize(canvasWidth, canvasHeight); }
        @Override public List<Slot> slots() { return List.copyOf(slots); }
        @Override public void save(Slot slot, SlotWrite write) {
            writes++;
            records.put(slot.id(), slot);
            if (failNextWrite) {
                failNextWrite = false;
                throw new IllegalStateException("synthetic mutation failure");
            }
        }
        @Override public void saveSubmission(Slot slot, SlotSignature signature) {
            writes++;
            records.put(slot.id(), slot);
        }
    }
}
