import "@testing-library/jest-dom/vitest";
import { act, render } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type {
  SignatureDraftDelta,
  SignatureDraftVersion,
} from "../api/signatureDraftApi.ts";
import { SignaturePad } from "./SignaturePad.tsx";
import type { SignaturePayload } from "./signaturePayload.ts";
import {
  ColdDraftContract,
  deferred,
  dispatchPointer,
  prepareCanvas,
  setupSignaturePadTestEnvironment,
  teardownSignaturePadTestEnvironment,
  version,
} from "./signaturePadTestSupport.ts";

beforeEach(setupSignaturePadTestEnvironment);
afterEach(teardownSignaturePadTestEnvironment);

it("publishes a cold first stroke progressively before pointerup", async () => {
  // Given
  const backend = new ColdDraftContract();
  const initial = deferred<void>();
  render(
    <SignaturePad
      onDraft={async (payload) => {
        await initial.promise;
        return backend.put(payload);
      }}
      onDraftDelta={backend.delta}
      onSubmit={vi.fn(() => Promise.resolve(true))}
    />,
  );
  const canvas = prepareCanvas();

  // When
  dispatchPointer(canvas, "pointerdown", 10, 10);
  dispatchPointer(canvas, "pointermove", 20, 20);
  await act(async () => vi.advanceTimersByTimeAsync(50));
  expect(backend.strokes).toEqual([]);
  initial.resolve(undefined);
  await act(async () => Promise.resolve());
  dispatchPointer(canvas, "pointermove", 30, 30);
  await act(async () => vi.advanceTimersByTimeAsync(50));

  // Then
  expect(backend.strokes).toEqual([[
    { x: 100_000, y: 100_000 },
    { x: 200_000, y: 200_000 },
    { x: 300_000, y: 300_000 },
  ]]);
  expect(backend.operations).toEqual(["begin", "append", "append"]);
});

it("retries the cold empty baseline without folding an active stroke into it", async () => {
  // Given
  const backend = new ColdDraftContract();
  const fullPayloads: SignaturePayload[] = [];
  const onDraft = vi.fn((payload: SignaturePayload) => {
    fullPayloads.push(payload);
    return fullPayloads.length === 1 ? Promise.resolve(null) : backend.put(payload);
  });
  render(
    <SignaturePad
      onDraft={onDraft}
      onDraftDelta={backend.delta}
      onSubmit={vi.fn(() => Promise.resolve(true))}
    />,
  );
  const canvas = prepareCanvas();

  // When
  dispatchPointer(canvas, "pointerdown", 10, 10);
  dispatchPointer(canvas, "pointermove", 20, 20);
  await act(async () => vi.advanceTimersByTimeAsync(50));

  // Then
  expect(fullPayloads).toEqual([
    { strokes: [], version: 1 },
    { strokes: [], version: 1 },
  ]);
  expect(backend.strokes).toEqual([[
    { x: 100_000, y: 100_000 },
    { x: 200_000, y: 200_000 },
  ]]);
});

it("does not drain held-stroke deltas after unmounting during initialization", async () => {
  // Given
  const initial = deferred<SignatureDraftVersion | null>();
  const onDraftDelta = vi.fn(() => Promise.resolve(version(1, 1)));
  const rendered = render(
    <SignaturePad
      onDraft={() => initial.promise}
      onDraftDelta={onDraftDelta}
      onSubmit={vi.fn(() => Promise.resolve(true))}
    />,
  );
  const canvas = prepareCanvas();
  dispatchPointer(canvas, "pointerdown", 10, 10);
  dispatchPointer(canvas, "pointermove", 20, 20);
  await act(async () => vi.advanceTimersByTimeAsync(50));

  // When
  rendered.unmount();
  initial.resolve(version(1, 0));
  await act(async () => Promise.resolve());

  // Then
  expect(onDraftDelta).not.toHaveBeenCalled();
});

it("flushes coalesced points in order within 50 ms and serializes delta requests", async () => {
  // Given
  const first = deferred<SignatureDraftVersion | null>();
  const deltas: SignatureDraftDelta[] = [];
  const onDraftDelta = vi.fn((delta: SignatureDraftDelta) => {
    deltas.push(delta);
    return deltas.length === 1 ? first.promise : Promise.resolve(
      version(delta.draftEpoch, delta.revision + 1),
    );
  });
  render(
    <SignaturePad
      onDraft={vi.fn(() => Promise.resolve(version(2, 0)))}
      onDraftDelta={onDraftDelta}
      onSubmit={vi.fn(() => Promise.resolve(true))}
    />,
  );
  const canvas = prepareCanvas();

  // When
  dispatchPointer(canvas, "pointerdown", 10, 10);
  dispatchPointer(canvas, "pointermove", 30, 30, [
    { clientX: 20, clientY: 20 },
    { clientX: 30, clientY: 30 },
  ]);
  await act(async () => vi.advanceTimersByTimeAsync(49));

  // Then
  expect(onDraftDelta).not.toHaveBeenCalled();
  await act(async () => vi.advanceTimersByTimeAsync(1));
  expect(onDraftDelta).toHaveBeenCalledTimes(1);
  expect(deltas[0]).toEqual(expect.objectContaining({
    operation: "begin",
    points: [{ x: 100_000, y: 100_000 }],
  }));
  expect(onDraftDelta).toHaveBeenCalledTimes(1);

  first.resolve(version(0, 1));
  await act(async () => Promise.resolve());
  expect(onDraftDelta).toHaveBeenCalledTimes(2);
  expect(deltas[1]).toEqual(expect.objectContaining({
    clientSequence: 2,
    operation: "append",
    points: [
      { x: 200_000, y: 200_000 },
      { x: 300_000, y: 300_000 },
    ],
    revision: 1,
  }));
});

it("retains failed deltas until a full reset succeeds then resumes newer points", async () => {
  // Given
  const reset = deferred<SignatureDraftVersion | null>();
  const fullPayloads: SignaturePayload[] = [];
  const deltas: SignatureDraftDelta[] = [];
  const onDraft = vi.fn((payload: SignaturePayload) => {
    fullPayloads.push(payload);
    if (fullPayloads.length === 1) {
      return Promise.resolve(version(2, 0));
    }
    return fullPayloads.length === 2 ? Promise.resolve(null) : reset.promise;
  });
  const onDraftDelta = vi.fn((delta: SignatureDraftDelta) => {
    deltas.push(delta);
    return deltas.length === 1
      ? Promise.resolve(null)
      : Promise.resolve(version(delta.draftEpoch, delta.revision + 1));
  });
  render(
    <SignaturePad
      onDraft={onDraft}
      onDraftDelta={onDraftDelta}
      onSubmit={vi.fn(() => Promise.resolve(true))}
    />,
  );
  const canvas = prepareCanvas();

  // When
  dispatchPointer(canvas, "pointerdown", 10, 10);
  dispatchPointer(canvas, "pointermove", 20, 20);
  await act(async () => vi.advanceTimersByTimeAsync(50));
  await act(async () => Promise.resolve());
  dispatchPointer(canvas, "pointermove", 30, 30);

  // Then
  expect(onDraft).toHaveBeenCalledTimes(1);
  dispatchPointer(canvas, "pointerup", 30, 30);
  await act(async () => vi.advanceTimersByTimeAsync(0));
  expect(onDraft).toHaveBeenCalledTimes(2);
  expect(fullPayloads[1]?.strokes[0]?.points).toEqual([
    { x: 100_000, y: 100_000 },
    { x: 200_000, y: 200_000 },
    { x: 300_000, y: 300_000 },
  ]);
  expect(canvas).toHaveAttribute("data-point-count", "3");

  await act(async () => Promise.resolve());
  dispatchPointer(canvas, "pointerdown", 40, 40);
  dispatchPointer(canvas, "pointerup", 50, 50);
  await act(async () => vi.advanceTimersByTimeAsync(0));
  expect(onDraft).toHaveBeenCalledTimes(3);
  expect(fullPayloads[2]?.strokes.map((stroke) => stroke.points.length)).toEqual([3, 2]);

  dispatchPointer(canvas, "pointerdown", 60, 60);

  reset.resolve(version(4, 0));
  await act(async () => vi.advanceTimersByTimeAsync(50));
  expect(onDraftDelta).toHaveBeenCalledTimes(2);
  expect(deltas[1]).toEqual({
    clientSequence: 1,
    draftEpoch: 4,
    operation: "begin",
    points: [{ x: 600_000, y: 600_000 }],
    revision: 0,
    strokeIndex: 2,
  });
});

it("sends a full draft heartbeat every 20 seconds", async () => {
  // Given
  const onDraft = vi.fn(() => Promise.resolve(version(1, 0)));
  render(
    <SignaturePad
      onDraft={onDraft}
      onDraftDelta={vi.fn(() => Promise.resolve(version(1, 1)))}
      onSubmit={vi.fn(() => Promise.resolve(true))}
    />,
  );

  // When / Then
  await act(async () => vi.advanceTimersByTimeAsync(19_999));
  expect(onDraft).toHaveBeenCalledTimes(1);
  await act(async () => vi.advanceTimersByTimeAsync(1));
  expect(onDraft).toHaveBeenCalledTimes(2);
  expect(onDraft).toHaveBeenCalledWith({ strokes: [], version: 1 });
});
