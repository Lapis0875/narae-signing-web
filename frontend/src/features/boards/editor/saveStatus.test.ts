import { describe, expect, it } from "vitest";
import {
  createSaveStatusState,
  isSavePending,
  type SaveOperation,
  type SaveOwner,
  saveStatusReducer,
  selectSaveState,
} from "./saveStatus.ts";

const color: SaveOwner = { kind: "color" };
const title: SaveOwner = { kind: "title" };
const slotA: SaveOwner = { kind: "layout", slotId: "slot-a" };
const slotB: SaveOwner = { kind: "layout", slotId: "slot-b" };

function operation(
  owner: SaveOperation["owner"],
  attempt: number,
  generation = 1,
): SaveOperation {
  return { attempt, generation, owner };
}

function reduce(
  state: ReturnType<typeof createSaveStatusState>,
  type:
    | "operation-started"
    | "operation-cancelled"
    | "operation-succeeded"
    | "operation-write-failed"
    | "operation-recovery-finished",
  value: SaveOperation,
) {
  return saveStatusReducer(state, { type, ...value });
}

describe("saveStatusReducer", () => {
  it("Given a held color retry When title settles Then only the color result owns the terminal state", () => {
    const firstColor = operation(color, 1);
    const layout = operation(slotA, 2);
    const retry = operation(color, 3);
    const titleSave = operation(title, 4);
    let state = createSaveStatusState(1);
    state = reduce(state, "operation-started", firstColor);
    state = reduce(state, "operation-write-failed", firstColor);
    state = reduce(state, "operation-recovery-finished", firstColor);
    state = reduce(state, "operation-started", layout);
    state = reduce(state, "operation-succeeded", layout);
    state = reduce(state, "operation-started", retry);
    state = reduce(state, "operation-started", titleSave);
    state = reduce(state, "operation-succeeded", titleSave);

    expect(selectSaveState(state, 1)).toBe("saving");
    expect(isSavePending(state, 1, color)).toBe(true);
    expect(
      selectSaveState(reduce(state, "operation-succeeded", retry), 1),
    ).toBe("saved");
    const failed = reduce(state, "operation-write-failed", retry);
    expect(selectSaveState(failed, 1)).toBe("failed");
  });

  it("Given layout is held When color succeeds Then layout keeps the projection saving", () => {
    const layout = operation(slotA, 1);
    const colorSave = operation(color, 2);
    let state = createSaveStatusState(1);
    state = reduce(state, "operation-started", layout);
    state = reduce(state, "operation-started", colorSave);
    state = reduce(state, "operation-succeeded", colorSave);

    expect(selectSaveState(state, 1)).toBe("saving");
    expect(isSavePending(state, 1, slotA)).toBe(true);
  });

  it.each([slotA, title])(
    "Given another owner failed When color succeeds Then that failure remains",
    (failedOwner) => {
      const failedSave = operation(failedOwner, 1);
      const colorSave = operation(color, 2);
      let state = createSaveStatusState(1);
      state = reduce(state, "operation-started", failedSave);
      state = reduce(state, "operation-write-failed", failedSave);
      state = reduce(state, "operation-recovery-finished", failedSave);
      state = reduce(state, "operation-started", colorSave);
      state = reduce(state, "operation-succeeded", colorSave);

      expect(selectSaveState(state, 1)).toBe("failed");
    },
  );

  it("Given two slot owners When one settles Then the other remains pending", () => {
    const first = operation(slotA, 1);
    const second = operation(slotB, 2);
    let state = createSaveStatusState(1);
    state = reduce(state, "operation-started", first);
    state = reduce(state, "operation-started", second);
    state = reduce(state, "operation-succeeded", first);

    expect(selectSaveState(state, 1)).toBe("saving");
    expect(isSavePending(state, 1, slotB)).toBe(true);
  });

  it("Given two accepted attempts for one slot When the newer settles first Then the older remains pending", () => {
    const olderMove = operation(slotA, 1);
    const newerUnplace = operation(slotA, 2);
    let state = createSaveStatusState(1);
    state = reduce(state, "operation-started", olderMove);
    state = reduce(state, "operation-started", newerUnplace);
    state = reduce(state, "operation-succeeded", newerUnplace);

    expect(selectSaveState(state, 1)).toBe("saving");
    expect(isSavePending(state, 1, slotA)).toBe(true);

    state = reduce(state, "operation-succeeded", olderMove);
    expect(selectSaveState(state, 1)).toBe("saved");
    expect(isSavePending(state, 1, slotA)).toBe(false);
  });

  it("Given two accepted attempts for one slot When the newer settles first and the older fails Then the owner remains failed", () => {
    const olderMove = operation(slotA, 1);
    const newerUnplace = operation(slotA, 2);
    let state = createSaveStatusState(1);
    state = reduce(state, "operation-started", olderMove);
    state = reduce(state, "operation-started", newerUnplace);
    state = reduce(state, "operation-succeeded", newerUnplace);
    state = reduce(state, "operation-write-failed", olderMove);
    state = reduce(state, "operation-recovery-finished", olderMove);

    expect(selectSaveState(state, 1)).toBe("failed");
    expect(isSavePending(state, 1, slotA)).toBe(false);
  });

  it("Given a debounce attempt is proven replaced When it is cancelled Then only the deliverable attempt remains", () => {
    const replacedMove = operation(slotA, 1);
    const deliverableMove = operation(slotA, 2);
    let state = createSaveStatusState(1);
    state = reduce(state, "operation-started", replacedMove);
    state = reduce(state, "operation-cancelled", replacedMove);
    state = reduce(state, "operation-started", deliverableMove);

    expect(selectSaveState(state, 1)).toBe("saving");
    state = reduce(state, "operation-succeeded", deliverableMove);
    expect(selectSaveState(state, 1)).toBe("saved");
    expect(isSavePending(state, 1, slotA)).toBe(false);
  });

  it("Given a failed attempt and an older outstanding attempt When the older succeeds Then it cannot recover the newer failure", () => {
    const olderMove = operation(slotA, 1);
    const newerUnplace = operation(slotA, 2);
    let state = createSaveStatusState(1);
    state = reduce(state, "operation-started", olderMove);
    state = reduce(state, "operation-started", newerUnplace);
    state = reduce(state, "operation-write-failed", newerUnplace);
    state = reduce(state, "operation-recovery-finished", newerUnplace);
    state = reduce(state, "operation-succeeded", olderMove);

    expect(selectSaveState(state, 1)).toBe("failed");
  });

  it("Given a write failed When queue recovery finishes Then recovery is not write success", () => {
    const layout = operation(slotA, 1);
    let state = createSaveStatusState(1);
    state = reduce(state, "operation-started", layout);
    state = reduce(state, "operation-write-failed", layout);
    state = reduce(state, "operation-recovery-finished", layout);

    expect(selectSaveState(state, 1)).toBe("failed");
    expect(isSavePending(state, 1, slotA)).toBe(false);
  });

  it("Given one owner failed When another owner retries Then the unrelated failure wins", () => {
    const layout = operation(slotA, 1);
    const firstColor = operation(color, 2);
    const colorRetry = operation(color, 3);
    let state = createSaveStatusState(1);
    state = reduce(state, "operation-started", layout);
    state = reduce(state, "operation-write-failed", layout);
    state = reduce(state, "operation-recovery-finished", layout);
    state = reduce(state, "operation-started", firstColor);
    state = reduce(state, "operation-write-failed", firstColor);
    state = reduce(state, "operation-recovery-finished", firstColor);
    state = reduce(state, "operation-started", colorRetry);

    expect(selectSaveState(state, 1)).toBe("failed");
    expect(isSavePending(state, 1, color)).toBe(true);
  });

  it("Given A then B then A again When A1 completes Then A2 ignores the stale attempt", () => {
    const staleA = operation(color, 1, 1);
    let state = createSaveStatusState(1);
    state = reduce(state, "operation-started", staleA);
    state = saveStatusReducer(state, {
      generation: 2,
      type: "generation-changed",
    });
    state = saveStatusReducer(state, {
      generation: 3,
      type: "generation-changed",
    });
    const currentA = operation(color, 2, 3);
    state = reduce(state, "operation-started", currentA);
    state = reduce(state, "operation-succeeded", staleA);

    expect(selectSaveState(state, 3)).toBe("saving");
    expect(isSavePending(state, 3, color)).toBe(true);
  });
});
