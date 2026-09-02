import { expect, test } from "@playwright/test"

const boardId = "00000000-0000-4000-8000-000000000011"
const slotId = "00000000-0000-4000-8000-000000000012"
const shareToken = "public-display-test-token"
const viewports = [
  { height: 812, label: "375x812", width: 375 },
  { height: 1024, label: "768x1024", width: 768 },
  { height: 800, label: "1280x800", width: 1280 },
] as const

for (const viewport of viewports) {
  test(`public display renders and clears live drafts at ${viewport.label}`, async ({ page }) => {
    let drafted = false
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
          window.addEventListener("qa-public-display-event", (event) => {
            if (this.closed || !(event instanceof CustomEvent) || typeof event.detail !== "object" || event.detail === null) return
            const type = Reflect.get(event.detail, "type")
            if (typeof type === "string") this.listeners.get(type)?.(event)
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
    await page.route(`**/api/v1/public/links/${shareToken}`, (route) => route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ state: "OPEN", title: "행사장 공개 화면" }),
    }))
    await page.route(`**/api/v1/public/links/${shareToken}/display/background`, (route) => route.fulfill({ status: 204 }))
    await page.route(`**/api/v1/public/links/${shareToken}/display/snapshot`, (route) => {
      snapshotRequests += 1
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({
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
            signature: null,
            width: 0.4,
            x: 0.2,
            y: 0.3,
          }],
        }),
      })
    })

    await page.goto(`/display/${shareToken}`)
    await expect(page.getByRole("heading", { name: "행사장 공개 화면" })).toBeVisible()
    await expect(page.getByTestId("full-view-canvas")).toBeVisible()
    await expect(page.getByTestId("submitted-signature")).toHaveCount(0)

    drafted = true
    const beforeDraft = snapshotRequests
    await page.evaluate(() => window.dispatchEvent(new CustomEvent("qa-public-display-event", {
      detail: { type: "signature-draft" },
    })))
    await expect(page.getByTestId("submitted-signature")).toBeVisible()
    await expect.poll(() => snapshotRequests > beforeDraft).toBe(true)
    await page.screenshot({ path: `../.omo/evidence/task-25-realtime-fullview/public-display-${viewport.label}-live.png` })

    drafted = false
    const beforeClear = snapshotRequests
    await page.evaluate(() => window.dispatchEvent(new CustomEvent("qa-public-display-event", {
      detail: { type: "signature-draft-cleared" },
    })))
    await expect(page.getByTestId("submitted-signature")).toHaveCount(0)
    await expect.poll(() => snapshotRequests > beforeClear).toBe(true)
    await expect(page.getByRole("button")).toHaveCount(0)
    await expect(page.getByRole("link")).toHaveCount(0)
    await expect(page.locator("body")).not.toContainText("홍길동")
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
    await page.screenshot({ path: `../.omo/evidence/task-25-realtime-fullview/public-display-${viewport.label}.png` })
    expect(browserErrors).toEqual([])
  })
}
