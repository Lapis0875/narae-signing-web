import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { SignatureDraftVersion } from "../api/signatureDraftApi.ts";
import { ColdDraftContract } from "./signaturePadTestSupport.ts";
import type { SignaturePoint } from "./signaturePayload.ts";
import { useSignatureDraftSync } from "./useSignatureDraftSync.ts";

const ACKNOWLEDGMENT_MS = 100;
const MAX_POINT_AGE_MS = 2 * ACKNOWLEDGMENT_MS + 50;

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(0);
});

afterEach(() => {
  cleanup();
  const remainingTimers = vi.getTimerCount();
  vi.useRealTimers();
  expect(remainingTimers, "unmounted hook and transport leave no timers").toBe(0);
});

it.each([60, 120])(
  "keeps acknowledged geometry within two RTTs plus 50 ms when input arrives at %i Hz",
  async (inputHz) => {
    // Given: the real hook talks to the stateful draft protocol over a 100 ms transport.
    const backend = new ColdDraftContract();
    const points: SignaturePoint[] = [];
    const generatedAt = new Map<number, number>();
    const acknowledgedAt = new Map<number, number>();
    const stopping = { current: false };
    let drawing = true;
    let inFlight = 0;
    let maximumInFlight = 0;
    let failures = 0;
    const transmit = async (
      save: () => Promise<SignatureDraftVersion | null>,
    ): Promise<SignatureDraftVersion | null> => {
      inFlight += 1;
      maximumInFlight = Math.max(maximumInFlight, inFlight);
      await new Promise<void>((resolve) => setTimeout(resolve, ACKNOWLEDGMENT_MS));
      const version = await save();
      inFlight -= 1;
      for (const point of backend.strokes.flat()) {
        if (!acknowledgedAt.has(point.x)) acknowledgedAt.set(point.x, Date.now());
      }
      return version;
    };
    const { result } = renderHook(() => useSignatureDraftSync({
      payload: () => ({ version: 1, strokes: [{ points: [...points] }] }),
      readyForFull: () => !drawing,
      sendDelta: (delta) => transmit(() => backend.delta(delta)),
      sendFull: (payload) => transmit(() => backend.put(payload)),
      setFailure: () => { failures += 1; },
      stopping,
    }));
    await act(async () => vi.advanceTimersByTimeAsync(ACKNOWLEDGMENT_MS));
    const strokeStartedAt = Date.now();
    let maximumVisibleAge = 0;

    // When: a held stroke supplies every sample for one second, then ends and drains.
    for (let index = 0; index <= inputHz; index += 1) {
      const sampleTime = strokeStartedAt + Math.round(index * 1_000 / inputHz);
      await act(async () => vi.advanceTimersByTimeAsync(sampleTime - Date.now()));
      const point = { x: index * 1_000, y: index * 2_000 };
      points.push(point);
      generatedAt.set(point.x, Date.now());
      act(() => result.current.queue({
        operation: index === 0 ? "begin" : "append",
        points: [point],
        strokeIndex: 0,
      }, index === 0));
      const visiblePoint = backend.strokes[0]?.at(-1);
      const visibleSampleTime = visiblePoint === undefined
        ? strokeStartedAt
        : generatedAt.get(visiblePoint.x) ?? strokeStartedAt;
      maximumVisibleAge = Math.max(maximumVisibleAge, Date.now() - visibleSampleTime);
    }
    drawing = false;
    act(() => result.current.queue({ operation: "end", points: [], strokeIndex: 0 }, true));
    await act(async () => vi.advanceTimersByTimeAsync((inputHz + 2) * ACKNOWLEDGMENT_MS));

    // Then: acknowledged geometry contains all samples, in order, with bounded age.
    expect(backend.strokes).toEqual([points]);
    expect(acknowledgedAt.size).toBe(points.length);
    expect(backend.operations[0]).toBe("begin");
    expect(backend.operations.at(-1)).toBe("end");
    expect(failures).toBe(0);
    expect(maximumInFlight).toBe(1);
    expect(inFlight).toBe(0);
    const pointAges = points.map((point) => {
      const arrived = acknowledgedAt.get(point.x);
      const generated = generatedAt.get(point.x);
      expect(arrived).toBeDefined();
      expect(generated).toBeDefined();
      return arrived === undefined || generated === undefined
        ? Number.POSITIVE_INFINITY
        : arrived - generated;
    });
    expect.soft(maximumVisibleAge, "oldest displayed coordinate during held input").toBeLessThanOrEqual(MAX_POINT_AGE_MS);
    expect.soft(Math.max(...pointAges), "oldest point on acknowledgment").toBeLessThanOrEqual(MAX_POINT_AGE_MS);
  },
);

it("keeps in-flight points immutable and preserves stroke boundaries while batching", async () => {
  const backend = new ColdDraftContract();
  const first = [{ x: 1, y: 1 }, { x: 2, y: 2 }, { x: 3, y: 3 }];
  const second = [{ x: 4, y: 4 }, { x: 5, y: 5 }];
  const { result } = renderHook(() => useSignatureDraftSync({
    payload: () => ({ version: 1, strokes: [first, second].map((points) => ({ points })) }),
    readyForFull: () => false,
    sendFull: (payload) => backend.put(payload),
    sendDelta: async (delta) => {
      const sentPoints = delta.points.map((point) => ({ ...point }));
      await new Promise<void>((resolve) => setTimeout(resolve, 100));
      expect(delta.points).toEqual(sentPoints);
      return backend.delta(delta);
    },
    setFailure: () => { throw new Error("Unexpected draft protocol failure"); },
    stopping: { current: false },
  }));
  await act(async () => vi.advanceTimersByTimeAsync(0));
  act(() => result.current.queue({ operation: "begin", points: first.slice(0, 1), strokeIndex: 0 }, true));
  await act(async () => vi.advanceTimersByTimeAsync(0));
  act(() => result.current.queue({ operation: "append", points: first.slice(1, 2), strokeIndex: 0 }));
  await act(async () => vi.advanceTimersByTimeAsync(100));
  act(() => {
    result.current.queue({ operation: "append", points: first.slice(2), strokeIndex: 0 });
    result.current.queue({ operation: "end", points: [], strokeIndex: 0 }, true);
    result.current.queue({ operation: "begin", points: second.slice(0, 1), strokeIndex: 1 });
    result.current.queue({ operation: "append", points: second.slice(1), strokeIndex: 1 });
    result.current.queue({ operation: "end", points: [], strokeIndex: 1 }, true);
  });
  await act(async () => vi.advanceTimersByTimeAsync(1_000));
  expect(backend.strokes).toEqual([first, second]);
  expect(backend.operations.filter((operation) => operation !== "append")).toEqual([
    "begin", "end", "begin", "end",
  ]);
});

it("keeps rapid short strokes current without merging their boundaries", async () => {
  const backend = new ColdDraftContract();
  const strokes: SignaturePoint[][] = [];
  const generatedAt = new Map<number, number>();
  let maximumPointAge = 0;
  const { result } = renderHook(() => useSignatureDraftSync({
    payload: () => ({ version: 1, strokes: strokes.map((points) => ({ points })) }),
    readyForFull: () => false,
    sendFull: (payload) => backend.put(payload),
    sendDelta: async (delta) => {
      await new Promise<void>((resolve) => setTimeout(resolve, 100));
      const saved = await backend.delta(delta);
      for (const point of delta.points) {
        maximumPointAge = Math.max(maximumPointAge, Date.now() - (generatedAt.get(point.x) ?? 0));
      }
      return saved;
    },
    setFailure: () => { throw new Error("Unexpected draft protocol failure"); },
    stopping: { current: false },
  }));
  await act(async () => vi.advanceTimersByTimeAsync(0));
  for (let strokeIndex = 0; strokeIndex < 4; strokeIndex += 1) {
    const points: SignaturePoint[] = [];
    strokes.push(points);
    for (let index = 0; index < 6; index += 1) {
      if (index > 0) await act(async () => vi.advanceTimersByTimeAsync(20));
      const point = { x: strokeIndex * 100 + index, y: strokeIndex * 100 + index };
      points.push(point);
      generatedAt.set(point.x, Date.now());
      act(() => result.current.queue({
        operation: index === 0 ? "begin" : "append", points: [point], strokeIndex,
      }, index === 0));
    }
    act(() => result.current.queue({ operation: "end", points: [], strokeIndex }, true));
    await act(async () => vi.advanceTimersByTimeAsync(50));
  }
  await act(async () => vi.advanceTimersByTimeAsync(2_000));
  expect(backend.strokes).toEqual(strokes);
  expect(backend.operations.filter((operation) => operation !== "append")).toEqual([
    "begin", "end", "begin", "end", "begin", "end", "begin", "end",
  ]);
  expect(maximumPointAge).toBeLessThanOrEqual(500);
});
