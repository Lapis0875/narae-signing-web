import "@testing-library/jest-dom/vitest"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, expect, it, vi } from "vitest"
import { setCsrfToken } from "../../../api/client.ts"
import { PublicSignerFlow } from "./PublicSignerFlow.tsx"

afterEach(() => {
  cleanup()
  setCsrfToken(null)
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

it("starts every QR visit at identity entry instead of reusing a prior signer session", async () => {
  const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
    state: "OPEN",
    title: "현장 서명",
  }), { headers: { "Content-Type": "application/json" } }))

  render(<PublicSignerFlow shareToken="share-1" />)

  await screen.findByTestId("public-signer-identify")
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(fetchMock).toHaveBeenCalledWith(
    "/api/v1/public/links/share-1",
    expect.objectContaining({ credentials: "same-origin", method: "GET" }),
  )
})

it("shows the entered signer identity above the pad after successful identification", async () => {
  vi.spyOn(document, "cookie", "get").mockReturnValue("XSRF-TOKEN=test-csrf-token")
  vi.stubGlobal("ResizeObserver", class {
    observe() {}
    disconnect() {}
  })
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null)
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    switch (input.toString()) {
      case "/api/v1/public/links/share-1":
        return new Response(JSON.stringify({ state: "OPEN", title: "현장 서명" }), {
          headers: { "Content-Type": "application/json" },
        })
      case "/api/v1/public/links/share-1/identify":
        return new Response(JSON.stringify({ identified: true }), {
          headers: { "Content-Type": "application/json" },
        })
      case "/api/v1/public/signing-session":
        return new Response(JSON.stringify({ state: "READY" }), {
          headers: { "Content-Type": "application/json" },
        })
      default:
        return new Response(JSON.stringify({ code: "INTERNAL_ERROR" }), { status: 500 })
    }
  })

  render(<PublicSignerFlow shareToken="share-1" />)

  await screen.findByTestId("public-signer-identify")
  fireEvent.change(screen.getByLabelText("소속사 (선택)"), { target: { value: "나래미디어" } })
  fireEvent.change(screen.getByLabelText("직책 (선택)"), { target: { value: "부장" } })
  fireEvent.change(screen.getByLabelText("이름"), { target: { value: "홍길동" } })
  fireEvent.click(screen.getByRole("button", { name: "정보 확인" }))

  await screen.findByTestId("public-signer-drawing")
  expect(screen.getByTestId("public-signer-identity")).toHaveTextContent("서명자: 나래미디어 · 부장 · 홍길동")
})

it("releases the signer lease through cancellation instead of draft clearing", async () => {
  vi.spyOn(document, "cookie", "get").mockReturnValue("XSRF-TOKEN=test-csrf-token")
  vi.stubGlobal("ResizeObserver", class {
    observe() {}
    disconnect() {}
  })
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null)
  const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    switch (input.toString()) {
      case "/api/v1/public/links/share-1":
        return new Response(JSON.stringify({ state: "OPEN", title: "현장 서명" }), {
          headers: { "Content-Type": "application/json" },
        })
      case "/api/v1/public/links/share-1/identify":
        return new Response(JSON.stringify({ identified: true }), {
          headers: { "Content-Type": "application/json" },
        })
      case "/api/v1/public/signing-session":
        return new Response(JSON.stringify({ state: "READY" }), {
          headers: { "Content-Type": "application/json" },
        })
      case "/api/v1/public/signing-session/cancel":
        return new Response(null, { status: 204 })
      default:
        return new Response(JSON.stringify({ code: "INTERNAL_ERROR" }), { status: 500 })
    }
  })

  render(<PublicSignerFlow shareToken="share-1" />)

  await screen.findByTestId("public-signer-identify")
  fireEvent.change(screen.getByLabelText("이름"), { target: { value: "홍길동" } })
  fireEvent.click(screen.getByRole("button", { name: "정보 확인" }))
  const canvas = await screen.findByTestId("signer-canvas")
  Object.defineProperties(canvas, {
    hasPointerCapture: { value: () => false },
    setPointerCapture: { value: () => undefined },
  })
  vi.spyOn(canvas, "getBoundingClientRect").mockReturnValue(new DOMRect(0, 0, 400, 200))
  fireEvent.pointerDown(canvas, { button: 0, clientX: 20, clientY: 20, isPrimary: true, pointerId: 1 })
  fireEvent.pointerUp(canvas, { button: 0, clientX: 80, clientY: 60, isPrimary: true, pointerId: 1 })
  fireEvent.click(screen.getByRole("button", { name: "서명 취소" }))

  await screen.findByTestId("public-signer-identify")
  expect(fetchMock).toHaveBeenCalledWith(
    "/api/v1/public/signing-session/cancel",
    expect.objectContaining({ method: "POST" }),
  )
  expect(fetchMock).not.toHaveBeenCalledWith(
    "/api/v1/public/signing-session/draft/clear",
    expect.anything(),
  )
})
