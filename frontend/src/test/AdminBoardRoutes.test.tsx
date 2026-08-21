import "@testing-library/jest-dom/vitest"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { apiRequest, setCsrfToken } from "../api/client.ts"
import { ToastProvider } from "../components/Toast.tsx"
import { previewRosterText } from "../features/boards/roster/rosterPreview.ts"
import { AppRouter } from "../routes/AppRouter.tsx"

const boardId = "11111111-1111-4111-8111-111111111111"
const entryId = "22222222-2222-4222-8222-222222222222"
const slotId = "33333333-3333-4333-8333-333333333333"
const board = {
  canvasHeight: 1080,
  canvasWidth: 1920,
  createdAt: "2026-08-21T00:00:00.000Z",
  id: boardId,
  shareLinkVersion: 1,
  status: "설정 중",
  title: "여름 발표회",
  updatedAt: "2026-08-21T00:00:00.000Z",
} as const
const rosterEntry = {
  id: entryId,
  identity: { job: "교사", name: "김나래", organization: "나래초" },
  slot: {
    backgroundColor: "TRANSPARENT",
    height: null,
    id: slotId,
    placementStatus: "UNPLACED",
    revision: 0,
    width: null,
    x: null,
    y: null,
  },
  submitted: false,
} as const

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    headers: { "Content-Type": "application/json" },
    status,
  })
}

function renderRoute(route: string) {
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false }, queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <ToastProvider><AppRouter /></ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function authenticatedResponse(path: string) {
  if (path.endsWith("/api/v1/auth/session")) {
    return json({ authenticated: true, expiresAt: new Date(Date.now() + 60_000).toISOString() })
  }
  return null
}

describe("administrator board routes", () => {
  beforeEach(() => {
    vi.spyOn(document, "cookie", "get").mockReturnValue("XSRF-TOKEN=csrf-test")
    Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
      configurable: true,
      value: vi.fn(function (this: HTMLDialogElement) {
        this.setAttribute("open", "")
      }),
    })
    Object.defineProperty(HTMLDialogElement.prototype, "close", {
      configurable: true,
      value: vi.fn(function (this: HTMLDialogElement) {
        this.removeAttribute("open")
      }),
    })
    setCsrfToken("csrf-test")
  })
  afterEach(() => {
    cleanup()
    Reflect.deleteProperty(HTMLDialogElement.prototype, "close")
    Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal")
    setCsrfToken(null)
    vi.restoreAllMocks()
  })

  it("creates a Korean-title board and navigates to its roster editor", async () => {
    // Given
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString()
      const auth = authenticatedResponse(path)
      if (auth !== null) return auth
      if (path.endsWith("/api/v1/admin/boards") && init?.method === "POST") {
        return json({ board, shareToken: "not-rendered" })
      }
      if (path.endsWith(`/api/v1/admin/boards/${boardId}`)) return json(board)
      if (path.endsWith(`/api/v1/admin/boards/${boardId}/roster`)) return json([])
      return json([])
    })
    renderRoute("/boards/new")

    // When
    fireEvent.change(await screen.findByLabelText("보드 제목"), { target: { value: "여름 발표회" } })
    fireEvent.click(screen.getByRole("button", { name: "보드 만들기" }))

    // Then
    expect(await screen.findByRole("heading", { level: 1, name: "여름 발표회" })).toBeInTheDocument()
    expect(screen.getByText("등록된 명단이 없습니다.")).toBeInTheDocument()
    expect(screen.queryByText("not-rendered")).not.toBeInTheDocument()
  })

  it("disables board creation while the server request is pending", async () => {
    // Given
    vi.spyOn(globalThis, "fetch").mockImplementation((input, init) => {
      const path = input.toString()
      const auth = authenticatedResponse(path)
      if (auth !== null) return Promise.resolve(auth)
      if (path.endsWith("/api/v1/admin/boards") && init?.method === "POST") {
        return new Promise(() => undefined)
      }
      return Promise.resolve(json([]))
    })
    renderRoute("/boards/new")

    // When
    fireEvent.change(await screen.findByLabelText("보드 제목"), { target: { value: "대기 중 보드" } })
    fireEvent.click(screen.getByRole("button", { name: "보드 만들기" }))

    // Then
    expect(await screen.findByRole("button", { name: "만드는 중" })).toBeDisabled()
  })

  it("renders exact roster values from the server snapshot", async () => {
    // Given
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const path = input.toString()
      const auth = authenticatedResponse(path)
      if (auth !== null) return auth
      if (path.endsWith("/roster")) return json([rosterEntry])
      return json(board)
    })

    // When
    renderRoute(`/boards/${boardId}/edit`)

    // Then
    expect(await screen.findByDisplayValue("나래초")).toBeInTheDocument()
    expect(screen.getByDisplayValue("교사")).toBeInTheDocument()
    expect(screen.getByDisplayValue("김나래")).toBeInTheDocument()
    expect(screen.getByLabelText("CSV 또는 XLSX 파일 선택")).toHaveAttribute("type", "file")
    expect(screen.getByText("선택한 파일 없음")).toBeInTheDocument()
  })

  it("adds a roster row and renders the accepted server value", async () => {
    // Given
    const addedEntry = {
      ...rosterEntry,
      identity: { job: "강사", name: "박하늘", organization: "별빛초" },
    }
    let rosterReads = 0
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString()
      const auth = authenticatedResponse(path)
      if (auth !== null) return auth
      if (path.endsWith("/roster") && init?.method === "POST") return json(addedEntry)
      if (path.endsWith("/roster")) return json(rosterReads++ === 0 ? [] : [addedEntry])
      return json(board)
    })
    renderRoute(`/boards/${boardId}/edit`)

    // When
    fireEvent.change(await screen.findByLabelText("소속", { selector: "#new-organization" }), { target: { value: "별빛초" } })
    fireEvent.change(screen.getByLabelText("직책", { selector: "#new-job" }), { target: { value: "강사" } })
    fireEvent.change(screen.getByLabelText("이름", { selector: "#new-name" }), { target: { value: "박하늘" } })
    fireEvent.click(screen.getByRole("button", { name: "명단 추가" }))

    // Then
    expect(await screen.findByDisplayValue("박하늘")).toBeInTheDocument()
    const request = fetchSpy.mock.calls.find(([input, init]) => input.toString().endsWith("/roster") && init?.method === "POST")
    expect(JSON.parse(request?.[1]?.body?.toString() ?? "null")).toEqual({
      job: "강사",
      name: "박하늘",
      organization: "별빛초",
    })
  })

  it("updates a roster row and renders the accepted server value", async () => {
    // Given
    const updatedEntry = { ...rosterEntry, identity: { ...rosterEntry.identity, job: "교감" } }
    let rosterReads = 0
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString()
      const auth = authenticatedResponse(path)
      if (auth !== null) return auth
      if (path.endsWith(`/roster/${entryId}`) && init?.method === "PATCH") return json(updatedEntry)
      if (path.endsWith("/roster")) return json(rosterReads++ === 0 ? [rosterEntry] : [updatedEntry])
      return json(board)
    })
    renderRoute(`/boards/${boardId}/edit`)

    // When
    fireEvent.change(await screen.findByDisplayValue("교사"), { target: { value: "교감" } })
    fireEvent.click(screen.getByRole("button", { name: "수정 저장" }))

    // Then
    await waitFor(() => expect(fetchSpy).toHaveBeenCalledWith(
      `/api/v1/admin/boards/${boardId}/roster/${entryId}`,
      expect.objectContaining({ method: "PATCH" }),
    ))
    const request = fetchSpy.mock.calls.find(([input, init]) =>
      input.toString().endsWith(`/roster/${entryId}`) && init?.method === "PATCH")
    expect(JSON.parse(request?.[1]?.body?.toString() ?? "null")).toEqual({
      job: "교감",
      name: "김나래",
      organization: "나래초",
    })
    expect(await screen.findByDisplayValue("교감")).toBeInTheDocument()
  })

  it("deletes a roster row only after confirmation", async () => {
    // Given
    let rosterReads = 0
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString()
      const auth = authenticatedResponse(path)
      if (auth !== null) return auth
      if (path.endsWith(`/roster/${entryId}`) && init?.method === "DELETE") return new Response(null, { status: 204 })
      if (path.endsWith("/roster")) return json(rosterReads++ === 0 ? [rosterEntry] : [])
      return json(board)
    })
    renderRoute(`/boards/${boardId}/edit`)

    // When
    await screen.findByDisplayValue("김나래")
    fireEvent.click(screen.getByRole("button", { name: "삭제" }))
    const dialog = screen.getByRole("dialog", { name: "확인" })
    expect(dialog).toHaveTextContent("선택한 명단을 삭제할까요?")
    fireEvent.click(within(dialog).getByRole("button", { name: "삭제" }))

    // Then
    expect(await screen.findByText("등록된 명단이 없습니다.")).toBeInTheDocument()
    expect(screen.queryByDisplayValue("김나래")).not.toBeInTheDocument()
  })

  it("preserves the accepted snapshot when file import is rejected", async () => {
    // Given
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString()
      const auth = authenticatedResponse(path)
      if (auth !== null) return auth
      if (path.endsWith("/roster/import") && init?.method === "POST") {
        return json({
          errors: [
            { code: "BLANK_NAME", row: 3 },
            { code: "DUPLICATE_IDENTITY", row: 2 },
          ],
        }, 400)
      }
      if (path.endsWith("/roster")) return json([rosterEntry])
      return json(board)
    })
    renderRoute(`/boards/${boardId}/edit`)
    const file = new File(["나래초,교사,김나래\n별빛초,교사,박하늘"], "roster.csv", { type: "text/csv" })

    // When
    fireEvent.change(await screen.findByLabelText("CSV 또는 XLSX 파일 선택"), { target: { files: [file] } })
    await waitFor(() => expect(screen.getByText("2명 미리보기")).toBeInTheDocument())
    fireEvent.click(screen.getByRole("button", { name: "파일 가져오기" }))

    // Then
    expect(await screen.findByRole("alert", { name: "명단 유지 안내" })).toBeInTheDocument()
    const serverErrors = screen.getByRole("alert", { name: "서버 입력 오류" })
    expect(serverErrors).toHaveTextContent("2행: 같은 명단이 중복되었습니다.")
    expect(serverErrors).toHaveTextContent("3행: 이름을 입력해 주세요.")
    expect(within(serverErrors).getAllByRole("listitem").map((item) => item.getAttribute("data-row"))).toEqual(["2", "3"])
    expect(screen.getByDisplayValue("김나래")).toBeInTheDocument()
  })

  it("uses a generic retention state for malformed import errors", async () => {
    // Given
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString()
      const auth = authenticatedResponse(path)
      if (auth !== null) return auth
      if (path.endsWith("/roster/import") && init?.method === "POST") {
        return json({ errors: [{ code: "BLANK_NAME", row: 51 }] }, 400)
      }
      if (path.endsWith("/roster")) return json([rosterEntry])
      return json(board)
    })
    renderRoute(`/boards/${boardId}/edit`)
    const file = new File(["나래초,교사,김나래"], "malformed.csv", { type: "text/csv" })

    // When
    fireEvent.change(await screen.findByLabelText("CSV 또는 XLSX 파일 선택"), { target: { files: [file] } })
    await waitFor(() => expect(screen.getByText("1명 미리보기")).toBeInTheDocument())
    fireEvent.click(screen.getByRole("button", { name: "파일 가져오기" }))

    // Then
    expect(await screen.findByRole("alert", { name: "명단 유지 안내" })).toBeInTheDocument()
    expect(screen.queryByRole("alert", { name: "서버 입력 오류" })).not.toBeInTheDocument()
    expect(screen.getByDisplayValue("김나래")).toBeInTheDocument()
    expect(screen.queryByText("BLANK_NAME")).not.toBeInTheDocument()
  })

  it("renders a generic reload state for malformed board data", async () => {
    // Given
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const path = input.toString()
      const auth = authenticatedResponse(path)
      if (auth !== null) return auth
      return json([{ id: boardId, title: "불완전" }])
    })

    // When
    renderRoute("/boards")

    // Then
    expect(await screen.findByRole("alert")).toHaveTextContent("보드 목록을 불러오지 못했습니다")
    expect(screen.getByRole("button", { name: "다시 불러오기" })).toBeInTheDocument()
  })

  it("exposes only bounded structured row details through the shared API path", async () => {
    // Given
    const cookieSpy = vi.spyOn(document, "cookie", "get")
    cookieSpy.mockReset()
    cookieSpy
      .mockReturnValueOnce("")
      .mockReturnValueOnce("XSRF-TOKEN=refreshed-csrf")
      .mockReturnValue("")
    setCsrfToken("cached-csrf")
    let requestCount = 0
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(async () => json(
      requestCount++ === 0
        ? {
            errors: [
              { code: "BLANK_NAME", row: 3 },
              { code: "DUPLICATE_IDENTITY", row: 2 },
            ],
            privateMessage: "must-not-escape",
          }
        : {
            errors: Array.from({ length: 51 }, (_, index) => ({
              code: "BLANK_NAME",
              row: (index % 50) + 1,
            })),
          },
      400,
    ))

    // When / Then
    const firstError = await apiRequest("/api/v1/admin/boards/test/roster/import", { method: "POST" }).catch(
      (error: unknown) => error,
    )
    expect(firstError).toMatchObject({
      details: [
        { code: "BLANK_NAME", row: 3 },
        { code: "DUPLICATE_IDENTITY", row: 2 },
      ],
    })
    expect(firstError).not.toHaveProperty("privateMessage")
    const oversizedError = await apiRequest("/api/v1/admin/boards/test/roster/import", { method: "POST" }).catch(
      (error: unknown) => error,
    )
    expect(oversizedError).toMatchObject({ details: [] })
    expect(new Headers(fetchSpy.mock.calls[0]?.[1]?.headers).get("X-XSRF-TOKEN")).toBe("cached-csrf")
    expect(new Headers(fetchSpy.mock.calls[1]?.[1]?.headers).get("X-XSRF-TOKEN")).toBe("refreshed-csrf")
  })
})

describe("roster preview markers", () => {
  it("orders safe duplicate and blank-name markers by row", () => {
    // Given
    const input = "나래초,교사,김나래\n나래초,교사,김나래\n별빛초,교사,"

    // When
    const preview = previewRosterText(input)

    // Then
    expect(preview.markers).toEqual([
      { message: "같은 명단이 중복되었습니다.", row: 2 },
      { message: "이름과 세 필드를 확인해 주세요.", row: 3 },
    ])
  })
})
