import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"
import type { Board } from "../list/boardApi.ts"
import { FinalPngAction } from "./FinalPngAction.tsx"

const boardId = "11111111-1111-4111-8111-111111111111"

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe("FinalPngAction", () => {
  it("Given a failed download When idle Then it retries only after an explicit click and uses no stale bytes", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(new Response(new Uint8Array([1, 2, 3]), {
        headers: { "Content-Type": "image/png" },
        status: 200,
      }))
    const createObjectUrl = vi.fn(() => "blob:fresh")
    const revokeObjectUrl = vi.fn()
    vi.stubGlobal("fetch", fetchMock)
    vi.stubGlobal("URL", { createObjectURL: createObjectUrl, revokeObjectURL: revokeObjectUrl })
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined)
    renderAction("마감/보관")

    fireEvent.click(screen.getByRole("button", { name: "최종 PNG 다운로드" }))
    expect((await screen.findByRole("alert")).textContent).toContain("다운로드하지 못했습니다")
    vi.useFakeTimers()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(createObjectUrl).not.toHaveBeenCalled()

    vi.useRealTimers()
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }))
    await waitFor(() => expect(createObjectUrl).toHaveBeenCalledTimes(1))
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock).toHaveBeenLastCalledWith(
      `/api/v1/admin/boards/${boardId}/final.png`,
      expect.objectContaining({ cache: "no-store", credentials: "same-origin", referrerPolicy: "no-referrer" }),
    )
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:fresh")
  })

  it("Given an open board When rendered Then download is unavailable", () => {
    renderAction("서명 진행")
    const button = screen.getByRole("button", { name: "최종 PNG 다운로드" })
    expect(button instanceof HTMLButtonElement).toBe(true)
    if (button instanceof HTMLButtonElement) expect(button.disabled).toBe(true)
  })
})

function renderAction(status: Board["status"]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 30_000 } } })
  queryClient.setQueryData(["admin", "boards", boardId], {
    canvasHeight: 1080,
    canvasWidth: 1920,
    createdAt: "2026-08-21T00:00:00.000Z",
    id: boardId,
    shareLinkVersion: 1,
    status,
    title: "Synthetic",
    updatedAt: "2026-08-21T00:00:00.000Z",
  } satisfies Board)
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
        <Routes><Route path="/boards/:boardId/edit" element={<FinalPngAction />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
