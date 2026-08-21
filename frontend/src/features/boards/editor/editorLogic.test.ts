import { describe, expect, it, vi } from "vitest"
import { collides, normalizedFromPixels } from "./geometry.ts"
import { SerializedSaveQueue } from "./saveQueue.ts"

describe("normalizedFromPixels", () => {
  it("Given pixel geometry When normalized Then it produces canvas-relative bounds", () => {
    expect(normalizedFromPixels({ height: 225, width: 400, x: 200, y: 225 }, { height: 900, width: 1600 }))
      .toEqual({ height: 0.25, width: 0.25, x: 0.125, y: 0.25 })
  })
})

describe("collides", () => {
  it("Given touching edges When compared Then they do not collide", () => {
    expect(collides(
      { height: 0.2, width: 0.2, x: 0, y: 0 },
      { height: 0.2, width: 0.2, x: 0.2, y: 0 },
    )).toBe(false)
  })
})

describe("SerializedSaveQueue", () => {
  it("Given rapid saves for two slots When debounce elapses Then each target keeps its latest save", async () => {
    vi.useFakeTimers()
    const queue = new SerializedSaveQueue()
    const staleA = vi.fn(async () => undefined), latestA = vi.fn(async () => undefined), latestB = vi.fn(async () => undefined)

    const staleResult = queue.enqueueDebounced("slot-a", staleA)
    await vi.advanceTimersByTimeAsync(40)
    const bResult = queue.enqueueDebounced("slot-b", latestB)
    const latestResult = queue.enqueueDebounced("slot-a", latestA)
    await vi.advanceTimersByTimeAsync(79)
    expect(staleA).not.toHaveBeenCalled(); expect(latestA).not.toHaveBeenCalled(); expect(latestB).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    await Promise.all([staleResult, bResult, latestResult])

    expect(staleA).not.toHaveBeenCalled(); expect(latestA).toHaveBeenCalledOnce(); expect(latestB).toHaveBeenCalledOnce()
    vi.useRealTimers()
  })

  it("Given rapid saves When debounce elapses Then only the latest save runs", async () => {
    vi.useFakeTimers()
    const queue = new SerializedSaveQueue()
    const first = vi.fn(async () => undefined)
    const latest = vi.fn(async () => undefined)

    const firstResult = queue.enqueueDebounced("slot-a", first)
    await vi.advanceTimersByTimeAsync(40)
    const latestResult = queue.enqueueDebounced("slot-a", latest)
    await vi.advanceTimersByTimeAsync(79)
    expect(first).not.toHaveBeenCalled()
    expect(latest).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    await Promise.all([firstResult, latestResult])

    expect(first).not.toHaveBeenCalled()
    expect(latest).toHaveBeenCalledOnce()
    vi.useRealTimers()
  })

  it("Given a coalesced save fails When another batch settles Then recovery blocks the next write", async () => {
    vi.useFakeTimers()
    let releaseRecovery: () => void = () => undefined
    const recoveryGate = new Promise<void>((resolve) => { releaseRecovery = resolve })
    const order: string[] = []
    const queue = new SerializedSaveQueue()

    const first = queue.enqueueDebounced("slot-a", async () => { order.push("first"); throw new Error("409") }, async () => { order.push("recovery:start"); await recoveryGate; order.push("recovery:end") })
    await vi.advanceTimersByTimeAsync(80)
    const stale = queue.enqueueDebounced("slot-a", async () => { order.push("stale") })
    const latest = queue.enqueueDebounced("slot-a", async () => { order.push("latest") })
    await vi.advanceTimersByTimeAsync(80)
    await vi.waitFor(() => expect(order).toContain("recovery:start"))
    expect(order).not.toContain("latest")
    releaseRecovery()
    await Promise.all([first, stale, latest])

    expect(order).toEqual(["first", "recovery:start", "recovery:end", "latest"])
    vi.useRealTimers()
  })

  it("Given a delayed first save When a second queues Then neither mutation replays", async () => {
    let releaseFirst: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { releaseFirst = resolve })
    const order: string[] = []
    const first = vi.fn(async () => { order.push("first:start"); await gate; order.push("first:end") })
    const second = vi.fn(async () => { order.push("second") })
    const queue = new SerializedSaveQueue()

    const firstResult = queue.enqueue(first)
    const secondResult = queue.enqueue(second)
    await vi.waitFor(() => expect(first).toHaveBeenCalledOnce())
    expect(second).not.toHaveBeenCalled()
    releaseFirst()
    await Promise.all([firstResult, secondResult])

    expect(order).toEqual(["first:start", "first:end", "second"])
    expect(first).toHaveBeenCalledOnce()
    expect(second).toHaveBeenCalledOnce()
  })

  it("Given save one fails When recovery is delayed Then save two waits for recovery", async () => {
    let releaseSave: () => void = () => undefined
    let releaseRecovery: () => void = () => undefined
    const saveGate = new Promise<void>((resolve) => { releaseSave = resolve })
    const recoveryGate = new Promise<void>((resolve) => { releaseRecovery = resolve })
    const order: string[] = []
    const queue = new SerializedSaveQueue()

    const first = queue.enqueue(async () => {
      order.push("first:start")
      await saveGate
      throw new Error("409")
    }, async () => {
      order.push("recovery:start")
      await recoveryGate
      order.push("recovery:end")
    }).catch((error: unknown) => error)
    const second = queue.enqueue(async () => { order.push("second") })

    releaseSave()
    await vi.waitFor(() => expect(order).toContain("recovery:start"))
    expect(order).not.toContain("second")
    releaseRecovery()
    await Promise.all([first, second])
    expect(order).toEqual(["first:start", "recovery:start", "recovery:end", "second"])
  })
})
