import { act, cleanup, render } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"
import { RealtimeBoardBridge } from "./RealtimeBoardBridge.tsx"

class FakeEventSource {
  static latest: FakeEventSource | null = null
  readonly listeners = new Map<string, EventListener>()
  onerror: (() => void) | null = null
  onopen: (() => void) | null = null

  constructor() { FakeEventSource.latest = this }
  addEventListener(type: string, listener: EventListenerOrEventListenerObject) {
    if (typeof listener === "function") this.listeners.set(type, listener)
  }
  close() {}
}

describe("editor realtime bridge", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); FakeEventSource.latest = null })

  it("refetches the authoritative editor snapshot for an event", async () => {
    // Given
    vi.stubGlobal("EventSource", FakeEventSource)
    const refetch = vi.fn().mockResolvedValue(undefined)
    render(<RealtimeBoardBridge boardId="00000000-0000-4000-8000-000000000001" refetchSnapshot={refetch} />)

    // When
    await act(async () => { FakeEventSource.latest?.listeners.get("signature-submitted")?.(new Event("signature-submitted")) })

    // Then
    expect(refetch).toHaveBeenCalledOnce()
  })

  it("runs one authoritative follow-up when an event arrives during refetch", async () => {
    // Given
    vi.stubGlobal("EventSource", FakeEventSource)
    let finishFirst = () => {}
    const firstRefetch = new Promise<void>((resolve) => { finishFirst = resolve })
    const refetch = vi.fn().mockReturnValueOnce(firstRefetch).mockResolvedValue(undefined)
    render(<RealtimeBoardBridge boardId="00000000-0000-4000-8000-000000000001" refetchSnapshot={refetch} />)
    await act(async () => {
      FakeEventSource.latest?.listeners.get("signature-submitted")?.(new Event("signature-submitted"))
      await Promise.resolve()
    })

    // When
    FakeEventSource.latest?.listeners.get("layout-updated")?.(new Event("layout-updated"))
    await act(async () => { finishFirst(); await firstRefetch; await Promise.resolve() })

    // Then
    expect(refetch).toHaveBeenCalledTimes(2)
  })
})
