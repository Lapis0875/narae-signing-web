import "@testing-library/jest-dom/vitest";
import { act, render } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { SignaturePad } from "./SignaturePad.tsx";
import {
  ColdDraftContract,
  deferred,
  dispatchPointer,
  prepareCanvas,
  setupSignaturePadTestEnvironment,
  teardownSignaturePadTestEnvironment,
} from "./signaturePadTestSupport.ts";

beforeEach(setupSignaturePadTestEnvironment);
afterEach(teardownSignaturePadTestEnvironment);

it("keeps publishing held points when a stroke crosses the heartbeat timer", async () => {
  // Given
  const backend = new ColdDraftContract();
  const onDraft = vi.fn(backend.put);
  render(<SignaturePad onDraft={onDraft} onDraftDelta={backend.delta}
    onSubmit={vi.fn(() => Promise.resolve(true))} />);
  const canvas = prepareCanvas();
  await act(async () => vi.advanceTimersByTimeAsync(19_000));
  dispatchPointer(canvas, "pointerdown", 10, 10);
  dispatchPointer(canvas, "pointermove", 20, 20);
  await act(async () => vi.advanceTimersByTimeAsync(50));

  // When
  await act(async () => vi.advanceTimersByTimeAsync(950));
  dispatchPointer(canvas, "pointermove", 30, 30);
  await act(async () => vi.advanceTimersByTimeAsync(50));

  // Then
  expect(backend.strokes).toEqual([[
    { x: 100_000, y: 100_000 }, { x: 200_000, y: 200_000 },
    { x: 300_000, y: 300_000 },
  ]]);
  expect(onDraft).toHaveBeenCalledTimes(1);
});

it("does not queue a heartbeat behind a pending request that overlaps a new held stroke", async () => {
  // Given
  const backend = new ColdDraftContract();
  const end = deferred<void>();
  const onDraft = vi.fn(backend.put);
  render(<SignaturePad onDraft={onDraft} onDraftDelta={async (delta) => {
    if (delta.operation === "end") await end.promise;
    return backend.delta(delta);
  }} onSubmit={vi.fn(() => Promise.resolve(true))} />);
  const canvas = prepareCanvas();
  await act(async () => vi.advanceTimersByTimeAsync(19_000));
  dispatchPointer(canvas, "pointerdown", 10, 10);
  dispatchPointer(canvas, "pointerup", 20, 20);
  await act(async () => vi.advanceTimersByTimeAsync(0));

  // When
  await act(async () => vi.advanceTimersByTimeAsync(1_000));
  dispatchPointer(canvas, "pointerdown", 30, 30);
  dispatchPointer(canvas, "pointermove", 40, 40);
  await act(async () => vi.advanceTimersByTimeAsync(50));
  end.resolve(undefined);
  await act(async () => Promise.resolve());

  // Then
  expect(backend.strokes).toEqual([
    [{ x: 100_000, y: 100_000 }, { x: 200_000, y: 200_000 }],
    [{ x: 300_000, y: 300_000 }, { x: 400_000, y: 400_000 }],
  ]);
  expect(onDraft).toHaveBeenCalledTimes(1);
});
