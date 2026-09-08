import "@testing-library/jest-dom/vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { SignaturePad } from "./SignaturePad.tsx";
import type { SignaturePayload } from "./signaturePayload.ts";
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

it("keeps draft synchronization stopped after successful cancellation", async () => {
  // Given
  const backend = new ColdDraftContract();
  const onDraft = vi.fn(backend.put);
  render(<SignaturePad onDraft={onDraft} onDraftDelta={backend.delta}
    onCancel={backend.clear} onSubmit={vi.fn(() => Promise.resolve(true))} />);
  const canvas = prepareCanvas();
  dispatchPointer(canvas, "pointerdown", 10, 10);
  dispatchPointer(canvas, "pointerup", 20, 20);
  await act(async () => vi.advanceTimersByTimeAsync(0));

  // When
  fireEvent.click(screen.getByRole("button", { name: "서명 취소" }));
  await act(async () => vi.advanceTimersByTimeAsync(20_000));

  // Then
  expect(onDraft).toHaveBeenCalledTimes(1);
  expect(backend.strokes).toEqual([]);
});

it("publishes a held redraw after clear advances the server epoch", async () => {
  // Given
  const backend = new ColdDraftContract();
  const onSubmit = vi.fn(() => Promise.resolve(true));
  render(<SignaturePad onDraft={backend.put} onDraftDelta={backend.delta}
    onClearDraft={backend.clear} onSubmit={onSubmit} />);
  const canvas = prepareCanvas();
  dispatchPointer(canvas, "pointerdown", 10, 10);
  dispatchPointer(canvas, "pointerup", 20, 20);
  await act(async () => vi.advanceTimersByTimeAsync(0));

  // When
  fireEvent.click(screen.getByRole("button", { name: "모두 지우기" }));
  await act(async () => Promise.resolve());
  dispatchPointer(canvas, "pointerdown", 30, 30);
  dispatchPointer(canvas, "pointermove", 40, 40);
  await act(async () => vi.advanceTimersByTimeAsync(50));

  // Then
  expect(backend.strokes).toEqual([[
    { x: 300_000, y: 300_000 }, { x: 400_000, y: 400_000 },
  ]]);
  dispatchPointer(canvas, "pointerup", 50, 50);
  await act(async () => vi.advanceTimersByTimeAsync(0));
  fireEvent.click(screen.getByRole("button", { name: "서명 제출" }));
  await act(async () => Promise.resolve());
  expect(onSubmit).toHaveBeenCalledWith({ strokes: [{ points: [
    { x: 300_000, y: 300_000 }, { x: 400_000, y: 400_000 },
    { x: 500_000, y: 500_000 },
  ] }], version: 1 });
  expect(backend.strokes[0]).toEqual([
    { x: 300_000, y: 300_000 }, { x: 400_000, y: 400_000 },
    { x: 500_000, y: 500_000 },
  ]);
});

it("retains held points while the post-clear baseline is pending", async () => {
  // Given
  const backend = new ColdDraftContract();
  const baseline = deferred<void>();
  const fullPayloads: SignaturePayload[] = [];
  render(<SignaturePad onDraft={async (payload) => {
    fullPayloads.push(payload);
    if (fullPayloads.length === 2) await baseline.promise;
    return backend.put(payload);
  }} onDraftDelta={backend.delta} onClearDraft={backend.clear}
    onSubmit={vi.fn(() => Promise.resolve(true))} />);
  const canvas = prepareCanvas();
  dispatchPointer(canvas, "pointerdown", 10, 10);
  dispatchPointer(canvas, "pointerup", 20, 20);
  await act(async () => vi.advanceTimersByTimeAsync(0));
  fireEvent.click(screen.getByRole("button", { name: "모두 지우기" }));
  await act(async () => Promise.resolve());

  // When
  dispatchPointer(canvas, "pointerdown", 30, 30);
  dispatchPointer(canvas, "pointermove", 40, 40);
  await act(async () => vi.advanceTimersByTimeAsync(50));
  expect(backend.strokes).toEqual([]);
  baseline.resolve(undefined);
  await act(async () => Promise.resolve());

  // Then
  expect(fullPayloads).toEqual([
    { strokes: [], version: 1 }, { strokes: [], version: 1 },
  ]);
  expect(backend.strokes).toEqual([[
    { x: 300_000, y: 300_000 }, { x: 400_000, y: 400_000 },
  ]]);
});

it("serializes clear behind pending initialization before publishing redraw", async () => {
  // Given
  const backend = new ColdDraftContract();
  const initial = deferred<void>();
  const onClearDraft = vi.fn(backend.clear);
  render(<SignaturePad onDraft={async (payload) => {
    await initial.promise;
    return backend.put(payload);
  }} onDraftDelta={backend.delta} onClearDraft={onClearDraft}
    onSubmit={vi.fn(() => Promise.resolve(true))} />);
  const canvas = prepareCanvas();
  dispatchPointer(canvas, "pointerdown", 10, 10);
  dispatchPointer(canvas, "pointerup", 20, 20);
  await act(async () => vi.advanceTimersByTimeAsync(0));

  // When
  fireEvent.click(screen.getByRole("button", { name: "모두 지우기" }));
  await act(async () => Promise.resolve());
  expect(onClearDraft).not.toHaveBeenCalled();
  initial.resolve(undefined);
  await act(async () => Promise.resolve());
  dispatchPointer(canvas, "pointerdown", 30, 30);
  await act(async () => vi.advanceTimersByTimeAsync(50));

  // Then
  expect(onClearDraft).toHaveBeenCalledTimes(1);
  expect(backend.strokes).toEqual([[{ x: 300_000, y: 300_000 }]]);
});

it("does not publish a cancelled held stroke when the post-clear baseline completes", async () => {
  // Given
  const backend = new ColdDraftContract();
  const baseline = deferred<void>();
  let fullAttempts = 0;
  render(<SignaturePad onDraft={async (payload) => {
    fullAttempts += 1;
    if (fullAttempts === 2) await baseline.promise;
    return backend.put(payload);
  }} onDraftDelta={backend.delta} onClearDraft={backend.clear}
    onSubmit={vi.fn(() => Promise.resolve(true))} />);
  const canvas = prepareCanvas();
  dispatchPointer(canvas, "pointerdown", 10, 10);
  dispatchPointer(canvas, "pointerup", 20, 20);
  await act(async () => vi.advanceTimersByTimeAsync(0));
  fireEvent.click(screen.getByRole("button", { name: "모두 지우기" }));
  await act(async () => Promise.resolve());

  // When
  dispatchPointer(canvas, "pointerdown", 30, 30);
  dispatchPointer(canvas, "pointermove", 40, 40);
  const cancelled = new Event("pointercancel", { bubbles: true });
  Object.defineProperty(cancelled, "pointerId", { value: 1 });
  fireEvent(canvas, cancelled);
  await act(async () => vi.advanceTimersByTimeAsync(50));
  baseline.resolve(undefined);
  await act(async () => Promise.resolve());

  // Then
  expect(backend.strokes).toEqual([]);
});

it.each(["clear", "cancel"] as const)("recovers a lost %s acknowledgement and failed baseline without dropping held points", async (operation) => {
  // Given
  const backend = new ColdDraftContract();
  let fullAttempts = 0;
  const clearWithLostAck = async () => {
    await backend.clear();
    return false;
  };
  render(<SignaturePad onDraft={(payload) => {
    fullAttempts += 1;
    return fullAttempts === 2 ? Promise.resolve(null) : backend.put(payload);
  }} onDraftDelta={backend.delta} onClearDraft={clearWithLostAck}
    onCancel={clearWithLostAck} onSubmit={vi.fn(() => Promise.resolve(true))} />);
  const canvas = prepareCanvas();
  dispatchPointer(canvas, "pointerdown", 10, 10);
  dispatchPointer(canvas, "pointerup", 20, 20);
  await act(async () => vi.advanceTimersByTimeAsync(0));

  // When
  fireEvent.click(screen.getByRole("button", {
    name: operation === "clear" ? "모두 지우기" : "서명 취소",
  }));
  await act(async () => Promise.resolve());
  dispatchPointer(canvas, "pointerdown", 30, 30);
  dispatchPointer(canvas, "pointermove", 40, 40);
  await act(async () => vi.advanceTimersByTimeAsync(50));

  // Then
  expect(fullAttempts).toBe(3);
  expect(backend.strokes).toEqual([[
    { x: 300_000, y: 300_000 }, { x: 400_000, y: 400_000 },
  ]]);
});
