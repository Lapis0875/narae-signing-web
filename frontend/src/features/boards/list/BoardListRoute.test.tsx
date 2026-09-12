import "@testing-library/jest-dom/vitest"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import { MemoryRouter } from "react-router-dom"
import { afterEach, expect, it, vi } from "vitest"
import { ToastProvider } from "../../../components/Toast.tsx"
import { BoardListRoute } from "./BoardListRoute.tsx"

const boards = [
  {
    canvasHeight: 1080,
    canvasWidth: 1620,
    createdAt: "2026-08-27T00:00:00.000Z",
    id: "00000000-0000-4000-8000-000000000001",
    shareLinkVersion: 1,
    signatureInkColor: "black",
    status: "설정 중",
    title: "준비 보드",
    updatedAt: "2026-08-27T00:00:00.000Z",
  },
  {
    canvasHeight: 1080,
    canvasWidth: 1620,
    createdAt: "2026-08-27T00:00:00.000Z",
    id: "00000000-0000-4000-8000-000000000002",
    shareLinkVersion: 1,
    signatureInkColor: "black",
    status: "서명 진행",
    title: "진행 보드",
    updatedAt: "2026-08-27T00:00:00.000Z",
  },
  {
    canvasHeight: 1080,
    canvasWidth: 1620,
    createdAt: "2026-08-27T00:00:00.000Z",
    id: "00000000-0000-4000-8000-000000000003",
    shareLinkVersion: 1,
    signatureInkColor: "black",
    status: "마감/보관",
    title: "보관 보드",
    updatedAt: "2026-08-27T00:00:00.000Z",
  },
] as const

afterEach(() => { cleanup(); vi.restoreAllMocks() })

it("opens every full view in a new window and offers deletion only outside active signing", async () => {
  // Given
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify(boards), {
    headers: { "Content-Type": "application/json" },
  }))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  // When
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ToastProvider><BoardListRoute /></ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await screen.findByRole("heading", { name: "준비 보드" })

  // Then
  const fullViews = screen.getAllByRole("link", { name: "전체보기" })
  expect(fullViews).toHaveLength(3)
  for (const fullView of fullViews) {
    expect(fullView).toHaveClass("board-button")
    expect(fullView).toHaveAttribute("target", "_blank")
    expect(fullView).toHaveAttribute("rel", "noopener noreferrer")
  }
  expect(screen.getAllByRole("button", { name: "보드 영구 삭제" })).toHaveLength(2)
})
