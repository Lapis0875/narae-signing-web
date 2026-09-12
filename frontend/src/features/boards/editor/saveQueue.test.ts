import { afterEach, describe, expect, it, vi } from "vitest";
import { SerializedSaveQueue } from "./saveQueue.ts";

afterEach(() => vi.useRealTimers());

describe("SerializedSaveQueue debounce lifecycle", () => {
  it("Given a pending debounce When the same target replaces it Then lifecycle reports only a safe replacement", async () => {
    vi.useFakeTimers();
    const queue = new SerializedSaveQueue();
    const first = vi.fn(async () => undefined);
    const latest = vi.fn(async () => undefined);
    const firstLifecycle: string[] = [];
    const latestLifecycle: string[] = [];

    const firstResult = queue.enqueueDebounced(
      "slot-a",
      first,
      undefined,
      (event) => firstLifecycle.push(event.type),
    );
    const latestResult = queue.enqueueDebounced(
      "slot-a",
      latest,
      undefined,
      (event) => latestLifecycle.push(event.type),
    );
    await vi.advanceTimersByTimeAsync(80);
    await Promise.all([firstResult, latestResult]);

    expect(first).not.toHaveBeenCalled();
    expect(latest).toHaveBeenCalledOnce();
    expect(firstLifecycle).toEqual(["replaced"]);
    expect(latestLifecycle).toEqual(["flushed"]);
  });

  it("Given a debounce flushed behind an active save When the same target queues again Then both remain deliverable", async () => {
    vi.useFakeTimers();
    let releaseBlocker: () => void = () => undefined;
    const blockerGate = new Promise<void>((resolve) => {
      releaseBlocker = resolve;
    });
    const queue = new SerializedSaveQueue();
    const order: string[] = [];
    const firstLifecycle: string[] = [];
    const secondLifecycle: string[] = [];
    const blocker = queue.enqueue(async () => {
      order.push("blocker:start");
      await blockerGate;
      order.push("blocker:end");
    });
    await vi.waitFor(() => expect(order).toEqual(["blocker:start"]));

    const first = queue.enqueueDebounced(
      "slot-a",
      async () => {
        order.push("first");
      },
      undefined,
      (event) => firstLifecycle.push(event.type),
    );
    await vi.advanceTimersByTimeAsync(80);
    const second = queue.enqueueDebounced(
      "slot-a",
      async () => {
        order.push("second");
      },
      undefined,
      (event) => secondLifecycle.push(event.type),
    );
    await vi.advanceTimersByTimeAsync(80);

    expect(firstLifecycle).toEqual(["flushed"]);
    expect(secondLifecycle).toEqual(["flushed"]);
    releaseBlocker();
    await Promise.all([blocker, first, second]);
    expect(order).toEqual(["blocker:start", "blocker:end", "first", "second"]);
  });
});
