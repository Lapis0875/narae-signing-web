import "@testing-library/jest-dom/vitest"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, expect, it, vi } from "vitest"
import { setCsrfToken } from "../../../api/client.ts"
import { ToastProvider } from "../../../components/Toast.tsx"
import { BoardEditorRoute } from "./BoardEditorRoute.tsx"
import { RosterManagementPanel } from "./RosterManagementPanel.tsx"

const boardId = "11111111-1111-4111-8111-111111111111"

afterEach(() => {
  cleanup()
  setCsrfToken(null)
  vi.restoreAllMocks()
})

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { headers: { "Content-Type": "application/json" }, status })
}

it("explains an incompatible board response in the editor toast", async () => {
  // Given
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const path = input.toString()
    if (path.endsWith("/background")) return new Response(null, { status: 204 })
    if (path.endsWith("/roster")) return json([])
    if (path.endsWith("/share")) return json({ shareToken: "safe-share", version: 1 })
    return json({
      canvasHeight: 1080,
      canvasWidth: 1920,
      createdAt: "2026-08-21T00:00:00.000Z",
      id: boardId,
      shareLinkVersion: 1,
      status: "생성 중",
      title: "계약 불일치 보드",
      updatedAt: "2026-08-21T00:00:00.000Z",
    })
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

  // When
  await screen.findByRole("alert")

  // Then
  await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("편집 화면을 불러오지 못했습니다."))
  expect(screen.getByRole("status")).toHaveTextContent("응답 형식 오류입니다.")
  expect(screen.getByRole("status")).toHaveTextContent("status")
})

it("explains a rejected roster addition in the toast", async () => {
  // Given
  setCsrfToken("test-csrf")
  vi.spyOn(globalThis, "fetch").mockResolvedValue(json({
    code: "ROSTER_INVALID",
    errors: [{ code: "DUPLICATE_IDENTITY", row: 1 }],
  }, 400))
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <ToastProvider><RosterManagementPanel boardId={boardId} entries={[]} /></ToastProvider>
    </QueryClientProvider>,
  )

  // When
  fireEvent.change(screen.getByLabelText("이름"), { target: { value: "나래" } })
  fireEvent.click(screen.getByRole("button", { name: "명단 추가" }))

  // Then
  await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("명단을 추가하지 못했습니다."))
  expect(screen.getByRole("status")).toHaveTextContent("같은 명단이 중복되었습니다.")
})
