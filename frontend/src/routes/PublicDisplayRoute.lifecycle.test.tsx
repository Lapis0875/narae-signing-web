import "@testing-library/jest-dom/vitest"
import { act, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"
import { publicLinkResponse, renderDisplay, resetPublicDisplayTest, shareToken, snapshotResponse, TestEventSource } from "./PublicDisplayRoute.test-support.tsx"

afterEach(resetPublicDisplayTest)

describe("public display terminal and release lifecycle", () => {
  it("releases every pagehide through a CSRF-authenticated keepalive request", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] })
    vi.stubGlobal("EventSource", TestEventSource)
    vi.spyOn(document, "cookie", "get").mockReturnValue("XSRF-TOKEN=release-csrf-token")
    const requests: { readonly credentials: RequestCredentials | undefined; readonly headers: Headers; readonly keepalive: boolean | undefined; readonly method: string; readonly url: string }[] = []
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = input.toString()
      requests.push({ credentials: init?.credentials, headers: new Headers(init?.headers), keepalive: init?.keepalive, method: init?.method ?? "GET", url })
      if (url === `/api/v1/public/links/${shareToken}`) return publicLinkResponse()
      if (url.endsWith("/claim") || url.endsWith("/heartbeat") || url.endsWith("/release")) return new Response(null, { status: 204 })
      if (url.endsWith("/title")) return Response.json({ title: "행사장 공개 화면" })
      if (url.endsWith("/background")) return new Response(null, { status: 204 })
      return snapshotResponse()
    })
    renderDisplay()
    await screen.findByTestId("full-view-canvas")

    await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })
    window.dispatchEvent(new PageTransitionEvent("pagehide"))
    window.dispatchEvent(new PageTransitionEvent("pagehide"))

    expect(requests.filter(({ url }) => url.endsWith("/heartbeat"))).toHaveLength(2)
    await waitFor(() => expect(requests.filter(({ url }) => url.endsWith("/release"))).toHaveLength(2))
    for (const release of requests.filter(({ url }) => url.endsWith("/release"))) {
      expect(release).toMatchObject({ credentials: "same-origin", keepalive: true, method: "POST", url: `/api/v1/public/links/${shareToken}/display/release` })
      expect(release.headers.get("X-XSRF-TOKEN")).toBe("release-csrf-token")
    }
  })

  it("does not send a CSRF-less release request during pagehide", async () => {
    vi.stubGlobal("EventSource", TestEventSource)
    vi.spyOn(document, "cookie", "get").mockReturnValue("")
    const requests: string[] = []
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString()
      requests.push(url)
      if (url === `/api/v1/public/links/${shareToken}`) return publicLinkResponse()
      if (url.endsWith("/claim")) return new Response(null, { status: 204 })
      if (url.endsWith("/title")) return Response.json({ title: "행사장 공개 화면" })
      if (url.endsWith("/background")) return new Response(null, { status: 204 })
      return snapshotResponse()
    })
    renderDisplay()
    await screen.findByTestId("full-view-canvas")

    window.dispatchEvent(new PageTransitionEvent("pagehide"))

    await act(async () => { await Promise.resolve() })
    expect(requests.filter((url) => url.endsWith("/release"))).toHaveLength(0)
  })

  it("retains the last good canvas during later network and 503 failures", async () => {
    vi.stubGlobal("EventSource", TestEventSource)
    let failure: "network" | "none" | "server" = "none"
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString()
      if (url === `/api/v1/public/links/${shareToken}`) return publicLinkResponse()
      if (url.endsWith("/claim")) return new Response(null, { status: 204 })
      if (url.endsWith("/title")) return Response.json({ title: "행사장 공개 화면" })
      if (url.endsWith("/background")) {
        if (failure === "network") throw new TypeError("offline")
        if (failure === "server") return Response.json({ code: "SERVICE_UNAVAILABLE" }, { status: 503 })
        return new Response(null, { status: 204 })
      }
      if (url.endsWith("/snapshot")) {
        if (failure === "network") throw new TypeError("offline")
        if (failure === "server") return Response.json({ code: "SERVICE_UNAVAILABLE" }, { status: 503 })
        return snapshotResponse()
      }
      return new Response(null, { status: 204 })
    })
    renderDisplay()
    await screen.findByTestId("full-view-canvas")

    failure = "network"
    await act(async () => { TestEventSource.current?.emit("board-updated") })
    await waitFor(() => expect(screen.getByTestId("full-view-canvas")).toBeVisible())
    failure = "server"
    await act(async () => { TestEventSource.current?.emit("board-updated") })

    await waitFor(() => expect(screen.getByTestId("full-view-canvas")).toBeVisible())
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
  })

  it("retains the canvas for heartbeat 503 but clears it for heartbeat 404", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] })
    vi.stubGlobal("EventSource", TestEventSource)
    let heartbeatStatus = 503
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString()
      if (url === `/api/v1/public/links/${shareToken}`) return publicLinkResponse()
      if (url.endsWith("/claim")) return new Response(null, { status: 204 })
      if (url.endsWith("/heartbeat")) return Response.json({ code: "PUBLIC_DISPLAY_UNAVAILABLE" }, { status: heartbeatStatus })
      if (url.endsWith("/title")) return Response.json({ title: "행사장 공개 화면" })
      if (url.endsWith("/background")) return new Response(null, { status: 204 })
      return snapshotResponse()
    })
    renderDisplay()
    await screen.findByTestId("full-view-canvas")

    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(screen.getByTestId("full-view-canvas")).toBeVisible()
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
    heartbeatStatus = 404
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(await screen.findByRole("alert")).toHaveTextContent("행사장 화면을 불러오지 못했습니다.")
    expect(screen.queryByTestId("full-view-canvas")).not.toBeInTheDocument()
    expect(screen.queryByText("이 화면의 표시 연결이 다른 화면으로 전환되었습니다.")).not.toBeInTheDocument()
  })

  it("clears the cached canvas for a later revoked-link 404 even when a stale snapshot completes", async () => {
    vi.stubGlobal("EventSource", TestEventSource)
    let revoked = false
    let resolveStaleSnapshot: (response: Response | PromiseLike<Response>) => void = (_response) => { throw new Error("Expected an in-flight snapshot request") }
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString()
      if (url === `/api/v1/public/links/${shareToken}`) return publicLinkResponse()
      if (url.endsWith("/claim")) return new Response(null, { status: 204 })
      if (url.endsWith("/title")) return Response.json({ title: "행사장 공개 화면" })
      if (url.endsWith("/background")) return revoked
        ? Response.json({ code: "PUBLIC_DISPLAY_UNAVAILABLE" }, { status: 404 })
        : new Response(null, { status: 204 })
      if (url.endsWith("/snapshot") && revoked) return new Promise<Response>((resolve) => { resolveStaleSnapshot = resolve })
      return snapshotResponse()
    })
    renderDisplay()
    await screen.findByTestId("full-view-canvas")

    revoked = true
    await act(async () => { TestEventSource.current?.emit("board-updated") })
    expect(await screen.findByRole("alert")).toHaveTextContent("행사장 화면을 불러오지 못했습니다.")
    resolveStaleSnapshot(snapshotResponse())
    await act(async () => { await Promise.resolve() })

    expect(screen.getByRole("alert")).toHaveTextContent("행사장 화면을 불러오지 못했습니다.")
    expect(screen.queryByTestId("full-view-canvas")).not.toBeInTheDocument()
    expect(screen.queryByText("이 화면의 표시 연결이 다른 화면으로 전환되었습니다.")).not.toBeInTheDocument()
  })
})
