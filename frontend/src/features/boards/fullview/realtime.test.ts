import { describe, expect, it, vi } from "vitest";
import type { FullViewSnapshot } from "./fullViewApi.ts";
import {
  mergePublicSnapshot,
  parsePublicDraftEvent,
  reconcilePublicDraft,
  reconnectDelay,
} from "./realtime.ts";

const firstSlotId = "00000000-0000-4000-8000-000000000002";
const secondSlotId = "00000000-0000-4000-8000-000000000003";

function snapshot(): FullViewSnapshot {
  return {
    backgroundPresent: false,
    boardId: "00000000-0000-4000-8000-000000000001",
    canvasHeight: 600,
    canvasWidth: 800,
    signatureInkColor: "black",
    slots: [firstSlotId, secondSlotId].map((id) => ({
      draftEpoch: 1,
      draftSignature: { strokes: [], version: 1 },
      height: 0.2,
      id,
      revision: 0,
      signature: null,
      width: 0.3,
      x: 0.1,
      y: 0.2,
    })),
  };
}

function draftEvent(
  revision: number,
  operation: "begin" | "append",
  x: number,
) {
  return parsePublicDraftEvent(
    JSON.stringify({
      draftEpoch: 1,
      operation,
      points: [{ x, y: x }],
      revision,
      slotId: firstSlotId,
      strokeIndex: 0,
    }),
  );
}

describe("public incremental realtime", () => {
  it("applies 1,2, ignores duplicate 2, and recovers on gap 4 without changing the other slot", () => {
    const initial = snapshot();
    const first = reconcilePublicDraft(initial, draftEvent(1, "begin", 10));
    if (first.kind !== "applied") throw new Error("revision 1 must apply");
    const second = reconcilePublicDraft(
      first.snapshot,
      draftEvent(2, "append", 20),
    );
    if (second.kind !== "applied") throw new Error("revision 2 must apply");

    expect(
      second.snapshot.slots[0]?.draftSignature?.strokes[0]?.points,
    ).toEqual([
      { x: 10, y: 10 },
      { x: 20, y: 20 },
    ]);
    expect(second.snapshot.slots[1]).toBe(initial.slots[1]);
    expect(
      reconcilePublicDraft(second.snapshot, draftEvent(2, "append", 20)),
    ).toEqual({ kind: "ignored" });
    expect(
      reconcilePublicDraft(second.snapshot, draftEvent(4, "append", 40)),
    ).toEqual({ kind: "recover" });
  });

  it("rejects malformed events at the boundary", () => {
    expect(() =>
      parsePublicDraftEvent('{"operation":"append","points":"bad"}'),
    ).toThrow();
  });

  it("applies only the next reset epoch and converges clear, cancel, and full-reset payloads", () => {
    const initial = snapshot();
    const fullReset = parsePublicDraftEvent(
      JSON.stringify({
        draftEpoch: 2,
        operation: "full-reset",
        points: [],
        revision: 0,
        signature: { strokes: [{ points: [{ x: 30, y: 40 }] }], version: 1 },
        slotId: firstSlotId,
        strokeIndex: -1,
      }),
    );
    const clear = parsePublicDraftEvent(
      JSON.stringify({
        draftEpoch: 3,
        operation: "clear",
        points: [],
        revision: 0,
        signature: { strokes: [], version: 1 },
        slotId: firstSlotId,
        strokeIndex: -1,
      }),
    );
    const cancel = parsePublicDraftEvent(
      JSON.stringify({
        draftEpoch: 4,
        operation: "clear",
        points: [],
        revision: 0,
        signature: null,
        slotId: firstSlotId,
        strokeIndex: -1,
      }),
    );

    const resetResult = reconcilePublicDraft(initial, fullReset);
    if (resetResult.kind !== "applied")
      throw new Error("full reset must apply");
    const clearResult = reconcilePublicDraft(resetResult.snapshot, clear);
    if (clearResult.kind !== "applied") throw new Error("clear must apply");
    const cancelResult = reconcilePublicDraft(clearResult.snapshot, cancel);

    expect(resetResult.snapshot.slots[0]?.draftSignature).toEqual({
      strokes: [{ points: [{ x: 30, y: 40 }] }],
      version: 1,
    });
    expect(clearResult.snapshot.slots[0]?.draftSignature).toEqual({
      strokes: [],
      version: 1,
    });
    expect(
      cancelResult.kind === "applied"
        ? cancelResult.snapshot.slots[0]?.draftSignature
        : undefined,
    ).toBeNull();
    expect(
      reconcilePublicDraft(initial, { ...fullReset, draftEpoch: 3 }),
    ).toEqual({ kind: "recover" });
  });

  it("ignores stale snapshots and accepts equal snapshots including submitted geometry", () => {
    const initial = snapshot();
    const applied = reconcilePublicDraft(initial, draftEvent(1, "begin", 10));
    if (applied.kind !== "applied") throw new Error("draft must apply");
    const equal = structuredClone(applied.snapshot);
    const equalSlot = equal.slots[0];
    if (equalSlot === undefined) throw new Error("fixture slot missing");
    const submittedPayload = {
      strokes: [{ points: [{ x: 10, y: 10 }] }],
      version: 1 as const,
    };
    equal.slots[0] = {
      ...equalSlot,
      draftSignature: null,
      signature: submittedPayload,
    };

    const afterStale = mergePublicSnapshot(applied.snapshot, snapshot());
    const afterEqual = mergePublicSnapshot(afterStale, equal);

    expect(afterStale.slots[0]?.revision).toBe(1);
    expect(afterStale.slots[0]?.draftSignature).toEqual(
      applied.snapshot.slots[0]?.draftSignature,
    );
    expect(afterEqual.slots[0]?.draftSignature).toBeNull();
    expect(afterEqual.slots[0]?.signature).toEqual(submittedPayload);
  });

  it("adopts authoritative snapshot color while retaining locally newer geometry", () => {
    // Given
    const initial = snapshot();
    const applied = reconcilePublicDraft(initial, draftEvent(1, "begin", 10));
    if (applied.kind !== "applied") throw new Error("draft must apply");
    const incoming = { ...snapshot(), signatureInkColor: "white" as const };

    // When
    const merged = mergePublicSnapshot(applied.snapshot, incoming);

    // Then
    expect(merged.signatureInkColor).toBe("white");
    expect(merged.slots[0]?.draftEpoch).toBe(1);
    expect(merged.slots[0]?.revision).toBe(1);
    expect(merged.slots[0]?.draftSignature?.strokes[0]?.points).toEqual([
      { x: 10, y: 10 },
    ]);
  });
});

describe("snapshot-driven realtime", () => {
  it("uses bounded exponential reconnect backoff", () => {
    // Given / When
    const delays = Array.from({ length: 8 }, (_, attempt) =>
      reconnectDelay(attempt),
    );

    // Then
    expect(delays).toEqual([
      250, 500, 1_000, 2_000, 4_000, 4_000, 4_000, 4_000,
    ]);
    expect(vi.isFakeTimers()).toBe(false);
  });
});
