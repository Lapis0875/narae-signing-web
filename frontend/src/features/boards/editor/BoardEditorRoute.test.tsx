import "@testing-library/jest-dom/vitest"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"
import { ToastProvider } from "../../../components/Toast.tsx"
import { BoardEditorRoute } from "./BoardEditorRoute.tsx"

const boardId = "11111111-1111-4111-8111-111111111111"
const board = {
  canvasHeight: 1080,
  canvasWidth: 1920,
  createdAt: "2026-08-21T00:00:00.000Z",
  id: boardId,
  shareLinkVersion: 1,
  status: "설정 중",
  title: "가을 서명 발표회",
  updatedAt: "2026-08-21T00:00:00.000Z",
} as const
const roster = [
  {
    id: "31111111-1111-4111-8111-111111111111",
    identity: { job: "담당", name: "한별", organization: "나래" },
    slot: { backgroundColor: "transparent", height: 0.2, id: "21111111-1111-4111-8111-111111111111", placementStatus: "PLACED", revision: 1, width: 0.2, x: 0, y: 0 },
    submitted: false,
  },
  {
    id: "32222222-2222-4222-8222-222222222222",
    identity: { job: "담당", name: "누리", organization: "나래" },
    slot: { backgroundColor: null, height: null, id: "22222222-2222-4222-8222-222222222222", placementStatus: "UNPLACED", revision: 0, width: null, x: null, y: null },
    submitted: false,
  },
] as const

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { headers: { "Content-Type": "application/json" }, status })
}

function renderEditor(background: () => Response) {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const path = input.toString()
    if (path.endsWith("/background")) return background()
    if (path.endsWith("/roster")) return json(roster)
    if (path.endsWith("/share")) return json({ shareToken: "safe-share", version: 1 })
    return json(board)
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
        <ToastProvider>
          <Routes><Route element={<BoardEditorRoute />} path="/boards/:boardId/edit" /></Routes>
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe("BoardEditorRoute", () => {
  it("Given the editor When it mounts Then owner realtime refetch connects", async () => {
    // Given
    const connections: string[] = []
    vi.stubGlobal("EventSource", class {
      onerror: (() => void) | null = null
      onopen: (() => void) | null = null
      constructor(url: string) { connections.push(url) }
      addEventListener() {}
      close() {}
    })

    // When
    renderEditor(() => new Response(null, { status: 204 }))
    await screen.findByRole("application", { name: "서명 보드 캔버스" })

    // Then
    expect(connections).toContain(`/api/v1/admin/boards/${boardId}/events`)
  })

  it("Given current background read failure When retry succeeds Then a safe blocking error recovers", async () => {
    let reads = 0
    renderEditor(() => reads++ === 0 ? json({ code: "OBJECT_KEY_MISSING", objectKey: "private/owner/board.png" }, 404) : new Response(null, { status: 204 }))

    expect(await screen.findByRole("alert")).toHaveTextContent("배경을 불러오지 못했습니다")
    expect(screen.getByRole("alert")).not.toHaveTextContent("OBJECT_KEY_MISSING")
    expect(screen.getByRole("alert")).not.toHaveTextContent("private/owner/board.png")
    expect(screen.getByLabelText("PNG 또는 JPEG")).toBeDisabled()

    fireEvent.click(screen.getByRole("button", { name: "배경 다시 불러오기" }))

    expect(await screen.findByRole("application", { name: "서명 보드 캔버스" })).toBeVisible()
    expect(screen.getByLabelText("PNG 또는 JPEG")).toBeEnabled()
  })

  it("Given the editor When controls render Then shared board button primitives are reused", async () => {
    renderEditor(() => new Response(null, { status: 204 }))

    await screen.findByRole("application", { name: "서명 보드 캔버스" })
    await waitFor(() => expect(screen.getAllByRole("button").length).toBeGreaterThan(5))
    for (const button of screen.getAllByRole("button")) expect(button).toHaveClass("board-button")
  })
})
