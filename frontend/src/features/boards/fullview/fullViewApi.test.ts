import { describe, expect, it } from "vitest";
import { fullViewSnapshotSchema } from "./fullViewApi.ts";

const snapshot = {
  backgroundPresent: false,
  boardId: "00000000-0000-4000-8000-000000000001",
  canvasHeight: 600,
  canvasWidth: 800,
  signatureInkColor: "white",
  slots: [
    {
      draftEpoch: 0,
      draftSignature: null,
      height: 0.2,
      id: "00000000-0000-4000-8000-000000000002",
      revision: 0,
      signature: null,
      width: 0.3,
      x: 0.1,
      y: 0.2,
    },
  ],
} as const;

describe("full view snapshot contract", () => {
  it("Given a color and background-free slot When parsed Then the strict snapshot is retained", () => {
    expect(fullViewSnapshotSchema.parse(snapshot)).toEqual(snapshot);
  });

  it.each([
    ["missing", undefined],
    ["null", null],
    ["unknown", "blue"],
    ["case variant", "WHITE"],
  ])(
    "Given %s ink When parsed Then the snapshot is rejected",
    (_label, signatureInkColor) => {
      const payload = { ...snapshot, signatureInkColor };
      if (signatureInkColor === undefined)
        Reflect.deleteProperty(payload, "signatureInkColor");
      expect(() => fullViewSnapshotSchema.parse(payload)).toThrow();
    },
  );

  it("Given a stale slot background When parsed Then the snapshot is rejected", () => {
    const [slot] = snapshot.slots;
    expect(() =>
      fullViewSnapshotSchema.parse({
        ...snapshot,
        slots: [{ ...slot, background: "transparent" }],
      }),
    ).toThrow();
  });
});
