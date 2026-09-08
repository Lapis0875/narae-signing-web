import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render } from "@testing-library/react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { vi } from "vitest"
import { PublicDisplayRoute } from "./PublicDisplayRoute.tsx"

export const shareToken = "public-display-test-token"
const boardId = "00000000-0000-4000-8000-000000000011"

export class TestEventSource {
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

export function renderDisplay(): void {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/display/${shareToken}`]}>
        <Routes><Route element={<PublicDisplayRoute />} path="/display/:shareToken" /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

export function snapshotResponse(): Response {
  return Response.json({
    backgroundPresent: false,
    boardId,
    canvasHeight: 600,
    canvasWidth: 800,
    slots: [],
  })
}

export function publicLinkResponse(): Response {
  return Response.json({ state: "OPEN", title: "행사장 공개 화면" })
}

export function resetPublicDisplayTest(): void {
  cleanup()
  TestEventSource.current = null
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
}
