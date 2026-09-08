import "@testing-library/jest-dom/vitest"
import { act, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"
import {
  publicLinkResponse,
  renderDisplay,
  resetPublicDisplayTest,
  shareToken,
  TestEventSource,
} from "./PublicDisplayRoute.test-support.tsx"

const boardId = "00000000-0000-4000-8000-000000000011"
const queryKeyPrefix = ["public", "boards", shareToken] as const

function signedSnapshotResponse(): Response {
  return Response.json({
    backgroundPresent: false,
    boardId,
    canvasHeight: 600,
    canvasWidth: 800,
    slots: [{
      background: "transparent",
      draftEpoch: 1,
      draftSignature: { strokes: [{ points: [{ x: 100, y: 200 }] }], version: 1 },
      height: 0.2,
      id: "00000000-0000-4000-8000-000000000012",
      revision: 1,
      signature: null,
      width: 0.3,
      x: 0.1,
      y: 0.2,
    }],
  })
}

afterEach(resetPublicDisplayTest)

describe("public display refetch retention", () => {
  it.each(["display-claim", "display-title"] as const)("keeps the good display connected when a later %s refetch returns 503", async (queryName) => {
    // Given
    vi.stubGlobal("EventSource", TestEventSource)
    vi.stubGlobal("ResizeObserver", class { observe() {} disconnect() {} })
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null)
    let failedQuery: "display-claim" | "display-title" | null = null
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString()
      if (url === `/api/v1/public/links/${shareToken}`) return publicLinkResponse()
      if (url.endsWith("/claim")) return failedQuery === "display-claim"
        ? Response.json({ code: "SERVICE_UNAVAILABLE" }, { status: 503 })
        : new Response(null, { status: 204 })
      if (url.endsWith("/title")) return failedQuery === "display-title"
        ? Response.json({ code: "SERVICE_UNAVAILABLE" }, { status: 503 })
        : Response.json({ title: "행사장 공개 화면" })
      if (url.endsWith("/background")) return new Response(null, { status: 204 })
      return signedSnapshotResponse()
    })
    const client = renderDisplay()
    await screen.findByTestId("full-view-canvas")
    const source = TestEventSource.current
    if (source === null) throw new Error("Expected a public display EventSource")
    await screen.findByTestId("submitted-signature")

    // When
    failedQuery = queryName
    await act(async () => { await client.refetchQueries({ queryKey: [...queryKeyPrefix, queryName] }) })

    // Then
    await waitFor(() => expect(source.closed).toBe(false))
    expect(screen.getByRole("heading", { name: "행사장 공개 화면" })).toBeVisible()
    expect(screen.getByTestId("full-view-canvas")).toBeVisible()
    expect(screen.getByTestId("submitted-signature")).toBeVisible()
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
  })

  it.each([
    { body: { code: "PUBLIC_DISPLAY_UNAVAILABLE" }, message: "행사장 화면을 불러오지 못했습니다.", status: 404 },
    { body: { code: "DISPLAY_ALREADY_CONNECTED", message: "다른 화면에서 이미 보드를 표시하고 있습니다." }, message: "다른 화면에서 이미 보드를 표시하고 있습니다.", status: 409 },
  ] as const)("terminates a later claim refetch with HTTP $status", async ({ body, message, status }) => {
    // Given
    vi.stubGlobal("EventSource", TestEventSource)
    vi.stubGlobal("ResizeObserver", class { observe() {} disconnect() {} })
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null)
    let terminalStatus: 404 | 409 | null = null
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString()
      if (url === `/api/v1/public/links/${shareToken}`) return publicLinkResponse()
      if (url.endsWith("/claim")) return terminalStatus === null
        ? new Response(null, { status: 204 })
        : Response.json(body, { status })
      if (url.endsWith("/title")) return Response.json({ title: "행사장 공개 화면" })
      if (url.endsWith("/background")) return new Response(null, { status: 204 })
      return signedSnapshotResponse()
    })
    const client = renderDisplay()
    await screen.findByTestId("full-view-canvas")

    // When
    terminalStatus = status
    await act(async () => { await client.refetchQueries({ queryKey: [...queryKeyPrefix, "display-claim"] }) })

    // Then
    expect(await screen.findByRole("alert")).toHaveTextContent(message)
    expect(screen.queryByTestId("full-view-canvas")).not.toBeInTheDocument()
  })
})
