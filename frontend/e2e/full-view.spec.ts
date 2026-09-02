import { expect, test, type Route } from "@playwright/test"

const boardId = "00000000-0000-4000-8000-000000000001"
const slotId = "00000000-0000-4000-8000-000000000002"
const viewports = [
  { height: 812, label: "375x812", width: 375 },
  { height: 1024, label: "768x1024", width: 768 },
  { height: 800, label: "1280x800", width: 1280 },
] as const

for (const viewport of viewports) {
  test(`full view uses authoritative snapshots at ${viewport.label}`, async ({ page }) => {
    let mode: "error" | "loading" | "normal" = "loading"
    let backgroundResolved = false
    let pendingBackground: Route | null = null
    let pendingSnapshot: Route | null = null
    let drafted = false
    let signed = false
    let snapshotRequests = 0
    const browserErrors: string[] = []

    await page.setViewportSize(viewport)
    page.on("console", (message) => {
      if (message.type() === "error") browserErrors.push(message.text())
    })
    page.on("pageerror", (error) => browserErrors.push(error.message))
    await page.addInitScript(() => {
      class QaEventSource {
        closed = false
        onerror: (() => void) | null = null
        onopen: (() => void) | null = null
        readonly listeners = new Map<string, EventListener>()

        constructor() {
          window.addEventListener("qa-sse-event", (event) => {
            if (this.closed || !(event instanceof CustomEvent) || typeof event.detail !== "object" || event.detail === null) return
            const type = Reflect.get(event.detail, "type")
            if (typeof type === "string") this.listeners.get(type)?.(event)
          })
          window.addEventListener("qa-sse-error", () => {
            if (!this.closed) this.onerror?.()
          })
          queueMicrotask(() => {
            if (!this.closed) this.onopen?.()
          })
        }

        addEventListener(type: string, listener: EventListenerOrEventListenerObject) {
          if (typeof listener === "function") this.listeners.set(type, listener)
        }

        close() {
          this.closed = true
        }
      }
      Object.defineProperty(window, "EventSource", { configurable: true, value: QaEventSource })
    })
    await page.route("**/api/v1/auth/session", (route) => route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ authenticated: true, expiresAt: "2099-01-01T00:00:00.000Z" }),
    }))
    await page.route(`**/api/v1/admin/boards/${boardId}/background`, async (route) => {
      if (!backgroundResolved) {
        pendingBackground = route
        return
      }
      await route.fulfill({ status: 204 })
    })

    const snapshotBody = () => JSON.stringify({
      backgroundPresent: false,
      boardId,
      canvasHeight: 600,
      canvasWidth: 800,
      slots: [{
        background: "white",
        draftSignature: drafted
          ? { version: 1, strokes: [{ points: [{ x: 100_000, y: 200_000 }, { x: 900_000, y: 800_000 }] }] }
          : null,
        height: 0.25,
        id: slotId,
        signature: signed
          ? { version: 1, strokes: [{ points: [{ x: 100_000, y: 200_000 }, { x: 900_000, y: 800_000 }] }] }
          : null,
        width: 0.4,
        x: 0.2,
        y: 0.3,
      }],
    })

    await page.route(`**/api/v1/admin/boards/${boardId}/snapshot`, async (route) => {
      snapshotRequests += 1
      if (mode === "loading") {
        pendingSnapshot = route
        return
      }
      if (mode === "error") {
        await route.fulfill({ contentType: "application/json", status: 500, body: JSON.stringify({ error: "unavailable" }) })
        return
      }
      await route.fulfill({ contentType: "application/json", body: snapshotBody() })
    })

    await page.goto(`/boards/${boardId}/full`)
    await expect(page.getByTestId("full-view-canvas")).toContainText("전체보기를 불러오는 중입니다.")

    mode = "normal"
    const initialSnapshot = pendingSnapshot
    if (initialSnapshot === null) throw new Error("Expected the initial snapshot request to remain pending")
    await initialSnapshot.fulfill({ contentType: "application/json", body: snapshotBody() })
    pendingSnapshot = null
    await expect(page.getByTestId("full-view-canvas")).toContainText("전체보기를 불러오는 중입니다.")
    await expect(page.getByRole("img", { name: "서명 보드 전체보기" })).toHaveCount(0)
    await page.screenshot({ path: `../.omo/evidence/task-25-realtime-fullview/browser-matrix/${viewport.label}-loading.png` })

    backgroundResolved = true
    const initialBackground = pendingBackground
    if (initialBackground === null) throw new Error("Expected the initial background request to remain pending")
    await initialBackground.fulfill({ status: 204 })
    pendingBackground = null
    await expect(page.getByTestId("full-view-canvas")).toBeVisible()
    await expect(page.getByTestId("submitted-signature")).toHaveCount(0)

    drafted = true
    const beforeEvents = snapshotRequests
    await page.evaluate(() => {
      for (const detail of [
        { id: 9, type: "signature-draft" },
        { id: 9, type: "signature-draft" },
      ]) {
        window.dispatchEvent(new CustomEvent("qa-sse-event", { detail }))
      }
    })
    await expect(page.getByTestId("submitted-signature")).toBeVisible()
    await expect.poll(() => snapshotRequests).toBe(beforeEvents + 1)
    await page.screenshot({ path: `../.omo/evidence/task-25-realtime-fullview/browser-matrix/${viewport.label}-draft.png` })

    drafted = false
    const beforeClear = snapshotRequests
    await page.evaluate(() => window.dispatchEvent(new CustomEvent("qa-sse-event", {
      detail: { id: 10, type: "signature-draft-cleared" },
    })))
    await expect(page.getByTestId("submitted-signature")).toHaveCount(0)
    await expect.poll(() => snapshotRequests).toBe(beforeClear + 1)
    await page.screenshot({ path: `../.omo/evidence/task-25-realtime-fullview/browser-matrix/${viewport.label}-draft-cleared.png` })

    signed = true
    const beforeSubmission = snapshotRequests
    await page.evaluate(() => {
      for (const detail of [
        { id: 9, type: "signature-submitted" },
        { id: 9, type: "signature-submitted" },
        { id: 8, type: "layout-updated" },
        { id: 11, type: "board-updated" },
      ]) {
        window.dispatchEvent(new CustomEvent("qa-sse-event", { detail }))
      }
    })
    await expect(page.getByTestId("submitted-signature")).toBeVisible()
    await expect.poll(() => snapshotRequests).toBe(beforeSubmission + 1)
    await expect(page.locator("main")).toHaveCount(1)
    await expect(page.getByRole("button")).toHaveCount(0)
    await expect(page.getByRole("link")).toHaveCount(0)
    await expect(page.locator(".slot-overlay, input, textarea, select")).toHaveCount(0)
    await expect(page.getByRole("heading", { name: "보드 전체보기" })).toBeVisible()
    await expect(page.locator("body")).not.toContainText("홍길동")
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
    await page.keyboard.press("Tab")
    expect(await page.evaluate(() => document.activeElement?.tagName)).toBe("BODY")
    await page.getByRole("heading", { name: "보드 전체보기" }).evaluate((heading) => heading.scrollIntoView({ block: "start" }))
    await page.screenshot({ path: `../.omo/evidence/task-25-realtime-fullview/browser-matrix/${viewport.label}-normal.png` })
    if (viewport.label === "1280x800") {
      await page.screenshot({ path: "../.omo/evidence/task-25-realtime-fullview/task-25-realtime-fullview.png", fullPage: true })
    }

    mode = "error"
    await page.evaluate(() => window.dispatchEvent(new CustomEvent("qa-sse-event", { detail: { id: 12, type: "board-updated" } })))
    await expect(page.getByTestId("full-view-canvas")).toContainText("전체보기를 불러오지 못했습니다.")
    await page.screenshot({ path: `../.omo/evidence/task-25-realtime-fullview/browser-matrix/${viewport.label}-query-error.png` })
    expect(browserErrors).toEqual([
      "Failed to load resource: the server responded with a status of 500 (Internal Server Error)",
      "api_request_failed {code: UNKNOWN, method: GET, requestId: null, route: /api/v1/admin/boards/:id/snapshot, status: 500}",
    ])
    browserErrors.length = 0

    mode = "normal"
    const beforeReconnect = snapshotRequests
    await page.evaluate(() => window.dispatchEvent(new Event("qa-sse-error")))
    await expect.poll(() => snapshotRequests).toBe(beforeReconnect + 1)
    await expect(page.getByTestId("submitted-signature")).toBeVisible()
    await page.getByRole("heading", { name: "보드 전체보기" }).evaluate((heading) => heading.scrollIntoView({ block: "start" }))
    await page.screenshot({ path: `../.omo/evidence/task-25-realtime-fullview/browser-matrix/${viewport.label}-recovery.png` })
    if (viewport.label === "1280x800") {
      await page.screenshot({ path: "../.omo/evidence/task-25-realtime-fullview/task-25-realtime-fullview-error.png", fullPage: true })
    }
    expect(browserErrors).toEqual([])
  })
}
