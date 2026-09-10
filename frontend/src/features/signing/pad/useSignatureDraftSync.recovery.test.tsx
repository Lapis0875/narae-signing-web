import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type {
  SignatureDraftDelta,
  SignatureDraftVersion,
} from "../api/signatureDraftApi.ts";
import type { SignaturePayload } from "./signaturePayload.ts";
import { useSignatureDraftSync } from "./useSignatureDraftSync.ts";

type TransportOperation =
  | { readonly kind: "delta"; readonly value: SignatureDraftDelta }
  | { readonly kind: "full"; readonly value: SignaturePayload };

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  const remainingTimers = vi.getTimerCount();
  vi.useRealTimers();
  expect(remainingTimers, "unmounted recovery hook leaves no timers").toBe(0);
});

it("retries the authoritative draft when transport comes online after offline recovery fails", async () => {
  // Given: initial sync succeeds, then both a delta and its immediate full recovery fail offline.
  const payload: SignaturePayload = {
    strokes: [{ points: [{ x: 100, y: 200 }] }],
    version: 1,
  };
  const operations: TransportOperation[] = [];
  let fullAttempts = 0;
  const sendFull = async (
    value: SignaturePayload,
  ): Promise<SignatureDraftVersion> => {
    operations.push({ kind: "full", value });
    fullAttempts += 1;
    if (fullAttempts === 2) throw new TypeError("offline full sync");
    return { draftEpoch: 0, revision: fullAttempts - 1 };
  };
  const { result } = renderHook(() =>
    useSignatureDraftSync({
      payload: () => payload,
      readyForFull: () => true,
      sendDelta: async (value) => {
        operations.push({ kind: "delta", value });
        throw new TypeError("offline delta");
      },
      sendFull,
      setFailure: () => undefined,
      stopping: { current: false },
    }),
  );
  await act(async () => vi.advanceTimersByTimeAsync(0));
  act(() =>
    result.current.queue(
      {
        operation: "begin",
        points: payload.strokes[0]?.points ?? [],
        strokeIndex: 0,
      },
      true,
    ),
  );
  await act(async () => vi.advanceTimersByTimeAsync(0));
  expect(operations.map(({ kind }) => kind)).toEqual(["full", "delta", "full"]);

  // When: the browser reports that transport is online again.
  await act(async () => {
    window.dispatchEvent(new Event("online"));
  });

  // Then: one authoritative full sync publishes the current payload and completes recovery.
  expect(operations.map(({ kind }) => kind)).toEqual([
    "full",
    "delta",
    "full",
    "full",
  ]);
  expect(operations.at(-1)).toEqual({ kind: "full", value: payload });
  await act(async () => {
    window.dispatchEvent(new Event("online"));
  });
  expect(operations).toHaveLength(4);
});

it("does not retry offline recovery after the hook unmounts", async () => {
  // Given: recovery remains pending after an offline delta and full sync both fail.
  const payload: SignaturePayload = {
    strokes: [{ points: [{ x: 100, y: 200 }] }],
    version: 1,
  };
  const operations: TransportOperation[] = [];
  let fullAttempts = 0;
  const { result, unmount } = renderHook(() =>
    useSignatureDraftSync({
      payload: () => payload,
      readyForFull: () => true,
      sendDelta: async (value) => {
        operations.push({ kind: "delta", value });
        throw new TypeError("offline delta");
      },
      sendFull: async (value) => {
        operations.push({ kind: "full", value });
        fullAttempts += 1;
        if (fullAttempts === 2) throw new TypeError("offline full sync");
        return { draftEpoch: 0, revision: fullAttempts - 1 };
      },
      setFailure: () => undefined,
      stopping: { current: false },
    }),
  );
  await act(async () => vi.advanceTimersByTimeAsync(0));
  act(() =>
    result.current.queue(
      {
        operation: "begin",
        points: payload.strokes[0]?.points ?? [],
        strokeIndex: 0,
      },
      true,
    ),
  );
  await act(async () => vi.advanceTimersByTimeAsync(0));
  expect(operations.map(({ kind }) => kind)).toEqual(["full", "delta", "full"]);

  // When: transport returns after the hook has unmounted.
  unmount();
  await act(async () => {
    window.dispatchEvent(new Event("online"));
  });

  // Then: no authoritative request escapes the disposed hook.
  expect(operations).toHaveLength(3);
});
