import { describe, expect, it, vi } from "vitest"
import { reconnectDelay } from "./realtime.ts"

describe("snapshot-driven realtime", () => {
  it("uses bounded exponential reconnect backoff", () => {
    // Given / When
    const delays = Array.from({ length: 8 }, (_, attempt) => reconnectDelay(attempt))

    // Then
    expect(delays).toEqual([250, 500, 1_000, 2_000, 4_000, 4_000, 4_000, 4_000])
    expect(vi.isFakeTimers()).toBe(false)
  })
})
