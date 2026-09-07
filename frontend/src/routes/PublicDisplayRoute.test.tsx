import "@testing-library/jest-dom/vitest"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, cleanup, render, screen, waitFor } from "@testing-library/react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"
import { PublicDisplayRoute } from "./PublicDisplayRoute.tsx"

const shareToken = "public-display-test-token"
const boardId = "00000000-0000-4000-8000-000000000011"

class TestEventSource {
  static current: TestEventSource | null = null
  readonly listeners = new Map<string, EventListenerOrEventListenerObject>()
  onerror: ((event: Event) => void) | null = null
  onopen: ((event: Event) => void) | null = null

  constructor(readonly url: string) {
    TestEventSource.current = this
    queueMicrotask(() => this.onopen?.(new Event("open")))
  }

  addEventListener(type: string, listener: EventListenerOrEventListenerObject): void {
    this.listeners.set(type, listener)
  }

  close(): void {}

  emit(type: string, data?: string): void {
    const listener = this.listeners.get(type)
    const event = data === undefined ? new Event(type) : new MessageEvent(type, { data })
    if (typeof listener === "function") listener(event)
    else listener?.handleEvent(event)
  }
}

function renderDisplay(): void {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/display/${shareToken}`]}>
        <Routes><Route element={<PublicDisplayRoute />} path="/display/:shareToken" /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function snapshotResponse(): Response {
  return Response.json({
    backgroundPresent: false,
    boardId,
    canvasHeight: 600,
    canvasWidth: 800,
    slots: [],
  })
}

function publicLinkResponse(): Response {
  return Response.json({ state: "OPEN", title: "행사장 공개 화면" })
}

afterEach(() => {
  cleanup()
  TestEventSource.current = null
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe("public display lease lifecycle", () => {
  it("requests no board content before claim succeeds", async () => {
    // Given
    const requests: string[] = []
    vi.spyOn(globalThis, "fetch").mockImplementation((input) => {
      const url = input.toString()
      requests.push(url)
      if (url === `/api/v1/public/links/${shareToken}`) return Promise.resolve(publicLinkResponse())
      return new Promise(() => undefined)
    })

    // When
    renderDisplay()

    // Then
    await waitFor(() => expect(requests).toEqual([
      `/api/v1/public/links/${shareToken}`,
      `/api/v1/public/links/${shareToken}/display/claim`,
    ]))
    expect(screen.queryByRole("heading")).not.toBeInTheDocument()
    expect(screen.queryByTestId("full-view-canvas")).not.toBeInTheDocument()
  })

  it("shows only the exact denial contract when another display owns the board", async () => {
    // Given
    const requests: string[] = []
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString()
      requests.push(url)
      if (url === `/api/v1/public/links/${shareToken}`) return publicLinkResponse()
      return Response.json({
        code: "DISPLAY_ALREADY_CONNECTED",
        message: "다른 화면에서 이미 보드를 표시하고 있습니다.",
      }, { status: 409 })
    })

    // When
    renderDisplay()

    // Then
    expect(await screen.findByRole("alert")).toHaveTextContent("다른 화면에서 이미 보드를 표시하고 있습니다.")
    expect(requests).toEqual([
      `/api/v1/public/links/${shareToken}`,
      `/api/v1/public/links/${shareToken}/display/claim`,
    ])
    expect(screen.queryByRole("heading")).not.toBeInTheDocument()
    expect(screen.queryByTestId("full-view-canvas")).not.toBeInTheDocument()
  })

  it("treats a malformed conflict as an ordinary initial error", async () => {
    // Given
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => input.toString() === `/api/v1/public/links/${shareToken}`
      ? publicLinkResponse()
      : Response.json({ code: "DISPLAY_ALREADY_CONNECTED", message: "wrong message" }, { status: 409 }))

    // When
    renderDisplay()

    // Then
    expect(await screen.findByRole("alert")).toHaveTextContent("행사장 화면을 불러오지 못했습니다.")
    expect(screen.queryByText("다른 화면에서 이미 보드를 표시하고 있습니다.")).not.toBeInTheDocument()
  })

  it("heartbeats every ten seconds and releases with a pagehide beacon", async () => {
    // Given
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] })
    vi.stubGlobal("EventSource", TestEventSource)
    const beacon = vi.fn(() => true)
    Object.defineProperty(navigator, "sendBeacon", { configurable: true, value: beacon })
    const requests: { readonly method: string; readonly url: string }[] = []
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = input.toString()
      requests.push({ method: init?.method ?? "GET", url })
      if (url === `/api/v1/public/links/${shareToken}`) return publicLinkResponse()
      if (url.endsWith("/claim") || url.endsWith("/heartbeat")) return new Response(null, { status: 204 })
      if (url.endsWith("/title")) return Response.json({ title: "행사장 공개 화면" })
      if (url.endsWith("/background")) return new Response(null, { status: 204 })
      return snapshotResponse()
    })
    renderDisplay()
    await screen.findByTestId("full-view-canvas")

    // When
    await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })
    window.dispatchEvent(new PageTransitionEvent("pagehide"))

    // Then
    expect(requests.filter(({ url }) => url.endsWith("/heartbeat"))).toHaveLength(2)
    expect(beacon).toHaveBeenCalledOnce()
    expect(beacon).toHaveBeenCalledWith(`/api/v1/public/links/${shareToken}/display/release`)
  })

  it("retains the last good canvas during later transport failures", async () => {
    // Given
    vi.stubGlobal("EventSource", TestEventSource)
    let transportDown = false
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString()
      if (url === `/api/v1/public/links/${shareToken}`) return publicLinkResponse()
      if (url.endsWith("/claim")) return new Response(null, { status: 204 })
      if (url.endsWith("/title")) return Response.json({ title: "행사장 공개 화면" })
      if (url.endsWith("/background")) {
        if (transportDown) throw new TypeError("offline")
        return new Response(null, { status: 204 })
      }
      if (url.endsWith("/snapshot")) {
        if (transportDown) throw new TypeError("offline")
        return snapshotResponse()
      }
      return new Response(null, { status: 204 })
    })
    renderDisplay()
    await screen.findByTestId("full-view-canvas")
    transportDown = true

    // When
    await act(async () => { TestEventSource.current?.emit("board-updated") })

    // Then
    await waitFor(() => expect(screen.getByTestId("full-view-canvas")).toBeVisible())
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
  })

  it("renders a contiguous draft without refetching and recovers a revision gap", async () => {
    vi.stubGlobal("EventSource", TestEventSource)
    vi.stubGlobal("ResizeObserver", class { observe() {} disconnect() {} })
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null)
    let snapshotRequests = 0
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString()
      if (url === `/api/v1/public/links/${shareToken}`) return publicLinkResponse()
      if (url.endsWith("/claim")) return new Response(null, { status: 204 })
      if (url.endsWith("/title")) return Response.json({ title: "행사장 공개 화면" })
      if (url.endsWith("/background")) return new Response(null, { status: 204 })
      snapshotRequests += 1
      return Response.json({
        backgroundPresent: false,
        boardId,
        canvasHeight: 600,
        canvasWidth: 800,
        slots: [{
          background: "transparent",
          draftEpoch: 1,
          draftSignature: { strokes: [], version: 1 },
          height: 0.2,
          id: "00000000-0000-4000-8000-000000000012",
          revision: 0,
          signature: null,
          width: 0.3,
          x: 0.1,
          y: 0.2,
        }],
      })
    })
    renderDisplay()
    await screen.findByTestId("full-view-canvas")
    await waitFor(() => expect(snapshotRequests).toBeGreaterThanOrEqual(1))
    const beforeDraft = snapshotRequests

    await act(async () => { TestEventSource.current?.emit("signature-draft", JSON.stringify({
      draftEpoch: 1,
      operation: "begin",
      points: [{ x: 100, y: 200 }],
      revision: 1,
      slotId: "00000000-0000-4000-8000-000000000012",
      strokeIndex: 0,
    })) })

    expect(screen.getByTestId("submitted-signature")).toBeVisible()
    expect(snapshotRequests).toBe(beforeDraft)

    await act(async () => { TestEventSource.current?.emit("signature-draft", JSON.stringify({
      draftEpoch: 1,
      operation: "append",
      points: [{ x: 300, y: 400 }],
      revision: 3,
      slotId: "00000000-0000-4000-8000-000000000012",
      strokeIndex: 0,
    })) })

    await waitFor(() => expect(snapshotRequests).toBeGreaterThan(beforeDraft))
  })

  it("replaces the canvas only after the definite replacement event", async () => {
    // Given
    vi.stubGlobal("EventSource", TestEventSource)
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString()
      if (url === `/api/v1/public/links/${shareToken}`) return publicLinkResponse()
      if (url.endsWith("/claim")) return new Response(null, { status: 204 })
      if (url.endsWith("/title")) return Response.json({ title: "행사장 공개 화면" })
      if (url.endsWith("/background")) return new Response(null, { status: 204 })
      return snapshotResponse()
    })
    renderDisplay()
    await screen.findByTestId("full-view-canvas")

    // When
    act(() => { TestEventSource.current?.emit("display-replaced") })

    // Then
    expect(screen.getByRole("alert")).toHaveTextContent("이 화면의 표시 연결이 다른 화면으로 전환되었습니다.")
    expect(screen.queryByTestId("full-view-canvas")).not.toBeInTheDocument()
  })

  it("replaces the canvas when a heartbeat receives the exact denial", async () => {
    // Given
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] })
    vi.stubGlobal("EventSource", TestEventSource)
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString()
      if (url === `/api/v1/public/links/${shareToken}`) return publicLinkResponse()
      if (url.endsWith("/claim")) return new Response(null, { status: 204 })
      if (url.endsWith("/heartbeat")) return Response.json({
        code: "DISPLAY_ALREADY_CONNECTED",
        message: "다른 화면에서 이미 보드를 표시하고 있습니다.",
      }, { status: 409 })
      if (url.endsWith("/title")) return Response.json({ title: "행사장 공개 화면" })
      if (url.endsWith("/background")) return new Response(null, { status: 204 })
      return snapshotResponse()
    })
    renderDisplay()
    await screen.findByTestId("full-view-canvas")

    // When
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })

    // Then
    expect(screen.getByRole("alert")).toHaveTextContent("다른 화면에서 이미 보드를 표시하고 있습니다.")
    expect(screen.queryByTestId("full-view-canvas")).not.toBeInTheDocument()
  })
})
