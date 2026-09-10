import { describe, expect, it } from "vitest";
import { rosterEntrySchema } from "./rosterApi.ts";

const entry = {
  id: "31111111-1111-4111-8111-111111111111",
  identity: { job: "담당", name: "한별", organization: "나래" },
  slot: {
    height: 0.2,
    id: "21111111-1111-4111-8111-111111111111",
    placementStatus: "PLACED",
    revision: 1,
    width: 0.2,
    x: 0,
    y: 0,
  },
  submitted: false,
} as const;

describe("roster entry contract", () => {
  it("Given a background-free slot When parsed Then placement geometry is retained", () => {
    expect(rosterEntrySchema.parse(entry)).toEqual(entry);
  });

  it("Given a stale backgroundColor field When parsed Then the roster entry is rejected", () => {
    expect(() =>
      rosterEntrySchema.parse({
        ...entry,
        slot: { ...entry.slot, backgroundColor: "transparent" },
      }),
    ).toThrow();
  });
});
