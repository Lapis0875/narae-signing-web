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
const existingEntry = {
  id: "31111111-1111-4111-8111-111111111111",
  identity: { job: "담당", name: "기존 명단", organization: "나래" },
  slot: { backgroundColor: "transparent", height: null, id: "21111111-1111-4111-8111-111111111111", placementStatus: "UNPLACED", revision: 0, width: null, x: null, y: null },
  submitted: false,
} as const

afterEach(() => {
  cleanup()
  setCsrfToken(null)
  vi.restoreAllMocks()
})

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { headers: { "Content-Type": "application/json", "X-Request-ID": "qa-request-32" }, status })
}

it("Given an incompatible editor response When loading fails Then a safe transient toast explains recovery", async () => {
  // Given
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const path = input.toString()
    if (path.endsWith("/background")) return new Response(null, { status: 204 })
    if (path.endsWith("/roster")) return json([])
    if (path.endsWith("/share")) return json({ shareToken: "safe-share", version: 1 })
    return json({ status: "contract mismatch", privateObjectKey: "private/board.png" })
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
        <ToastProvider durationMs={50}>
          <Routes><Route element={<BoardEditorRoute />} path="/boards/:boardId/edit" /></Routes>
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )

  // When
  await screen.findByRole("alert")

  // Then
  expect(screen.getByRole("status")).toHaveTextContent("서버 응답 확인에 실패했습니다. 다시 시도해 주세요.")
  expect(screen.getByRole("status")).not.toHaveTextContent("contract mismatch")
  expect(screen.getByRole("status")).not.toHaveTextContent("private/board.png")
  await waitFor(() => expect(screen.getByRole("status")).toBeEmptyDOMElement())
})

it("Given a rejected roster addition When the server refuses it Then the draft and accepted snapshot remain visible", async () => {
  // Given
  setCsrfToken("test-csrf")
  vi.spyOn(globalThis, "fetch").mockResolvedValue(json({
    code: "ROSTER_INVALID",
    errors: [{ code: "DUPLICATE_IDENTITY", row: 1 }],
    message: "private duplicate identity",
    requestId: "qa-request-32",
  }, 400))
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <ToastProvider><RosterManagementPanel boardId={boardId} entries={[existingEntry]} /></ToastProvider>
    </QueryClientProvider>,
  )

  // When
  fireEvent.change(screen.getByLabelText("이름", { selector: "#new-name" }), { target: { value: "나래" } })
  fireEvent.click(screen.getByRole("button", { name: "명단 추가" }))

  // Then
  await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("같은 명단이 중복되었습니다. 소속, 직책, 이름을 확인해 주세요."))
  expect(screen.getByLabelText("이름", { selector: "#new-name" })).toHaveValue("나래")
  expect(screen.getByDisplayValue("기존 명단")).toBeVisible()
  expect(screen.getByRole("status")).not.toHaveTextContent("private duplicate identity")
})
