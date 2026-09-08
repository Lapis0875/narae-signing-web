import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { z } from "zod";
import { setCsrfToken } from "../../../api/client.ts";
import {
  appendSignatureDraft,
  updateSignatureDraft,
} from "../api/signatureDraftApi.ts";
import { ColdDraftContract } from "./signaturePadTestSupport.ts";
import { useSignatureDraftSync } from "./useSignatureDraftSync.ts";

const HTTP_BODY_LIMIT = 65_536;
const pointSchema = z.object({
  x: z.number().int().min(0).max(1_000_000),
  y: z.number().int().min(0).max(1_000_000),
});
const deltaSchema = z.object({
  operation: z.enum(["begin", "append", "end"]),
  clientSequence: z.number().int().nonnegative(),
  draftEpoch: z.number().int().nonnegative(),
  revision: z.number().int().nonnegative(),
  strokeIndex: z.number().int().min(0).max(127),
  points: z.array(pointSchema).max(4_096),
});
const payloadSchema = z.object({
  version: z.literal(1),
  strokes: z.array(z.object({ points: z.array(pointSchema) })),
});

beforeEach(() => {
  vi.useFakeTimers();
  vi.spyOn(document, "cookie", "get").mockReturnValue("XSRF-TOKEN=body-limit-test");
});

afterEach(() => {
  cleanup();
  const remainingTimers = vi.getTimerCount();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  setCsrfToken(null);
  expect(remainingTimers, "unmounted hook and transport leave no timers").toBe(0);
});

it.each([
  { batchedOperation: "begin", entrySize: 1 },
  { batchedOperation: "append", entrySize: 1 },
  { batchedOperation: "begin", entrySize: 65 },
  { batchedOperation: "append", entrySize: 65 },
] as const)(
  "keeps queued $batchedOperation batches of $entrySize-point entries within the HTTP cap",
  async ({ batchedOperation, entrySize }) => {
    // Given: actual API serialization meets the HTTP cap before the stateful draft contract.
    const backend = new ColdDraftContract();
    const strokes = [
      Array.from({ length: 4_094 }, (_, index) => ({
        x: 1_000_000,
        y: index % 2 === 0 ? 1_000_000 : 1_000_000 - index,
      })),
      [{ x: 999_999, y: 1_000_000 }, { x: 1_000_000, y: 1_000_000 }],
    ];
    const requestBytes: number[] = [];
    const sentDeltas: z.infer<typeof deltaSchema>[] = [];
    let failures = 0;
    let inFlight = 0;
    let maximumInFlight = 0;
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.stubGlobal("fetch", vi.fn(async (path: string, init: RequestInit) => {
      if (typeof init.body !== "string") throw new TypeError("Expected serialized JSON body");
      const bytes = new TextEncoder().encode(init.body).byteLength;
      requestBytes.push(bytes);
      inFlight += 1;
      maximumInFlight = Math.max(maximumInFlight, inFlight);
      await new Promise<void>((resolve) => setTimeout(resolve, 100));
      inFlight -= 1;
      if (bytes > HTTP_BODY_LIMIT) {
        return Response.json({ code: "request_too_large" }, { status: 413 });
      }
      const wire: unknown = JSON.parse(init.body);
      if (path === "/api/v1/public/signing-session/draft") {
        return Response.json(await backend.put(payloadSchema.parse(wire)));
      }
      expect(path).toBe("/api/v1/public/signing-session/draft/delta");
      const delta = deltaSchema.parse(wire);
      sentDeltas.push(delta);
      const saved = await backend.delta(delta);
      return saved === null
        ? Response.json({ code: "invalid_delta" }, { status: 409 })
        : Response.json(saved);
    }));
    const { result } = renderHook(() => useSignatureDraftSync({
      payload: () => ({ version: 1, strokes: strokes.map((points) => ({ points })) }),
      readyForFull: () => false,
      sendDelta: appendSignatureDraft,
      sendFull: updateSignatureDraft,
      setFailure: () => { failures += 1; },
      stopping: { current: false },
    }));
    await act(async () => vi.advanceTimersByTimeAsync(100));

    // When: a full 4,096-point signature backs up behind a 100 ms acknowledgment.
    for (const [strokeIndex, points] of strokes.entries()) {
      for (let index = 0; index < points.length; index += entrySize) {
        act(() => result.current.queue({
          operation: index === 0 ? "begin" : "append",
          points: points.slice(index, index + entrySize),
          strokeIndex,
        }, index === 0));
        if (strokeIndex === 0 && index === 0 && batchedOperation === "append") {
          await act(async () => vi.advanceTimersByTimeAsync(0));
        }
      }
      act(() => result.current.queue({ operation: "end", points: [], strokeIndex }, true));
    }
    await act(async () => vi.advanceTimersByTimeAsync(2_000));

    // Then: the wire cap, ordered geometry, boundaries and single-flight contract all hold.
    expect(Math.max(...requestBytes)).toBeLessThanOrEqual(HTTP_BODY_LIMIT);
    expect(failures).toBe(0);
    expect(backend.strokes).toEqual(strokes);
    expect(backend.operations.filter((operation) => operation !== "append")).toEqual([
      "begin", "end", "begin", "end",
    ]);
    expect(sentDeltas.some((delta) => delta.operation === batchedOperation && delta.points.length > 1)).toBe(true);
    expect(maximumInFlight).toBe(1);
    expect(inFlight).toBe(0);
  },
);
