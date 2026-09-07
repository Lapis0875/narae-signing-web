import { expect, test, type BrowserContext, type Page, type Route } from "@playwright/test"
import { createHash } from "node:crypto"
import { mkdir, readFile, stat, writeFile } from "node:fs/promises"
import path from "node:path"

const boardId = "00000000-0000-4000-8000-000000000011"
const slotId = "00000000-0000-4000-8000-000000000012"
const shareToken = "visual-final-token"
const evidenceDir = path.resolve("../.omo/evidence/public-display-live-ink")
const viewports = [
  { height: 812, label: "375x812", width: 375 },
  { height: 1024, label: "768x1024", width: 768 },
  { height: 800, label: "1280x800", width: 1280 },
] as const

type Runtime = {
  readonly boardBounds?: { readonly height: number; readonly width: number; readonly x: number; readonly y: number }
  readonly horizontalOverflow: boolean
  readonly scenarioId: string
  readonly verticalOverflow: boolean
  readonly viewport: { readonly height: number; readonly width: number }
}

async function settle(page: Page): Promise<void> {
  await page.evaluate(async () => {
    await document.fonts.ready
    await new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
  })
}

async function metrics(page: Page, scenarioId: string): Promise<Runtime> {
  const viewport = page.viewportSize()
  if (viewport === null) throw new Error("Visual matrix viewport is unavailable")
  const board = page.getByTestId("full-view-canvas")
  const boardBounds = await board.count() === 0 ? undefined : await board.boundingBox() ?? undefined
  const overflow = await page.evaluate(() => ({
    horizontal: document.documentElement.scrollWidth > document.documentElement.clientWidth,
    vertical: document.documentElement.scrollHeight > document.documentElement.clientHeight,
  }))
  return {
    boardBounds,
    horizontalOverflow: overflow.horizontal,
    scenarioId,
    verticalOverflow: overflow.vertical,
    viewport,
  }
}

async function installEventSource(context: BrowserContext): Promise<void> {
  await context.addInitScript(() => {
    Object.defineProperty(navigator, "sendBeacon", { configurable: true, value: () => true })
    class VisualEventSource {
      closed = false
      onerror: (() => void) | null = null
      onopen: (() => void) | null = null
      readonly listeners = new Map<string, EventListener>()

      constructor() {
        window.addEventListener("visual-event", (event) => {
          if (this.closed || !(event instanceof CustomEvent) || typeof event.detail !== "object" || event.detail === null) return
          const type = Reflect.get(event.detail, "type")
          const data = Reflect.get(event.detail, "data")
          if (typeof type === "string") this.listeners.get(type)?.(
            typeof data === "string" ? new MessageEvent(type, { data }) : new Event(type),
          )
        })
        queueMicrotask(() => this.onopen?.())
      }

      addEventListener(type: string, listener: EventListenerOrEventListenerObject): void {
        if (typeof listener === "function") this.listeners.set(type, listener)
      }

      close(): void { this.closed = true }
    }
    Object.defineProperty(window, "EventSource", { configurable: true, value: VisualEventSource })
  })
}

async function routePublic(context: BrowserContext, claimStatus: () => 204 | 409): Promise<void> {
  await context.route(`**/api/v1/public/links/${shareToken}`, (route) => route.fulfill({
    json: { state: "OPEN", title: "행사장 공개 화면" },
  }))
  await context.route(`**/api/v1/public/links/${shareToken}/display/**`, (route: Route) => {
    const suffix = new URL(route.request().url()).pathname.split("/").at(-1)
    if (suffix === "claim") {
      return claimStatus() === 409
        ? route.fulfill({ status: 409, json: { code: "DISPLAY_ALREADY_CONNECTED", message: "다른 화면에서 이미 보드를 표시하고 있습니다." } })
        : route.fulfill({ status: 204 })
    }
    if (suffix === "heartbeat" || suffix === "release") return route.fulfill({ status: 204 })
    if (suffix === "title") return route.fulfill({ json: { title: "행사장 공개 화면" } })
    if (suffix === "background") return route.fulfill({ status: 204 })
    if (suffix === "snapshot") return route.fulfill({ json: {
      backgroundPresent: false,
      boardId,
      canvasHeight: 600,
      canvasWidth: 800,
      slots: [{
        background: "white",
        draftEpoch: 1,
        draftSignature: { version: 1, strokes: [] },
        height: 0.3,
        id: slotId,
        revision: 0,
        signature: null,
        width: 0.5,
        x: 0.25,
        y: 0.35,
      }],
    } })
    return route.fulfill({ body: ": connected\n\n", contentType: "text/event-stream" })
  })
}

async function capturePublic(page: Page, state: "progressive" | "denial" | "old-replaced", label: string): Promise<Runtime> {
  await page.goto(`/display/${shareToken}`)
  const scenarioId = `public-display-${state}-${label}`
  if (state === "denial") {
    await expect(page.getByRole("alert")).toHaveText("다른 화면에서 이미 보드를 표시하고 있습니다.")
    await expect(page.getByTestId("full-view-canvas")).toHaveCount(0)
  } else {
    await expect(page.getByTestId("full-view-canvas")).toBeVisible()
    if (state === "progressive") {
      await page.evaluate(() => window.dispatchEvent(new CustomEvent("visual-event", { detail: {
        data: JSON.stringify({
          draftEpoch: 1,
          operation: "begin",
          points: [{ x: 100_000, y: 200_000 }, { x: 500_000, y: 500_000 }, { x: 900_000, y: 800_000 }],
          revision: 1,
          slotId: "00000000-0000-4000-8000-000000000012",
          strokeIndex: 0,
        }),
        type: "signature-draft",
      } })))
      await expect(page.locator(`[data-slot-id="${slotId}"] canvas`)).toBeVisible()
    } else {
      await page.evaluate(() => window.dispatchEvent(new CustomEvent("visual-event", { detail: { type: "display-replaced" } })))
      await expect(page.getByRole("alert")).toHaveText("이 화면의 표시 연결이 다른 화면으로 전환되었습니다.")
      await expect(page.getByTestId("full-view-canvas")).toHaveCount(0)
    }
  }
  await settle(page)
  const result = await metrics(page, scenarioId)
  expect(result.horizontalOverflow).toBe(false)
  expect(result.verticalOverflow).toBe(false)
  if (result.boardBounds !== undefined) {
    expect(result.boardBounds.y + result.boardBounds.height).toBeLessThanOrEqual(result.viewport.height)
    expect(Math.abs(result.boardBounds.width / result.boardBounds.height - 4 / 3)).toBeLessThan(0.01)
  }
  const filename = state === "old-replaced"
    ? `visual-final-old-replaced-${label}.png`
    : `visual-final-public-${state}-${label}.png`
  await page.screenshot({ path: path.join(evidenceDir, filename) })
  return result
}

async function routeAdmin(context: BrowserContext): Promise<void> {
  await context.addCookies([{ name: "XSRF-TOKEN", value: "csrf-redacted", url: "http://127.0.0.1:4173" }])
  await context.route("**/api/v1/**", (route: Route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === "/api/v1/auth/session") return route.fulfill({ json: { authenticated: true, expiresAt: "2026-09-08T00:00:00.000Z" } })
    if (pathname === `/api/v1/admin/boards/${boardId}`) return route.fulfill({ json: {
      canvasHeight: 600, canvasWidth: 800, createdAt: "2026-09-07T00:00:00.000Z", id: boardId,
      shareLinkVersion: 1, status: "설정 중", title: "가을 서명 발표회", updatedAt: "2026-09-07T00:00:00.000Z",
    } })
    if (pathname === `/api/v1/admin/boards/${boardId}/roster`) return route.fulfill({ json: [] })
    if (pathname === `/api/v1/admin/boards/${boardId}/share`) return route.fulfill({ json: { shareToken, version: 1 } })
    if (pathname === `/api/v1/admin/boards/${boardId}/background`) return route.fulfill({ status: 204 })
    if (pathname === `/api/v1/admin/boards/${boardId}/events`) return route.fulfill({ body: ": connected\n\n", contentType: "text/event-stream" })
    return route.fallback()
  })
}

async function captureAdmin(page: Page, label: string): Promise<Runtime> {
  await page.goto(`/boards/${boardId}/edit`)
  const share = page.getByRole("region", { name: "공유" })
  await share.getByRole("button", { name: "행사장 화면 교체" }).click()
  await expect(page.getByRole("dialog", { name: "확인" })).toContainText("현재 행사장 화면의 표시 연결을 종료합니다")
  await settle(page)
  const result = await metrics(page, `admin-share-panel-confirm-${label}`)
  expect(result.horizontalOverflow).toBe(false)
  await page.screenshot({ path: path.join(evidenceDir, `visual-final-admin-confirm-${label}.png`) })
  return result
}

test("captures the public display final visual matrix", async ({ context }) => {
  test.setTimeout(120_000)
  await mkdir(evidenceDir, { recursive: true })
  let claimStatus: 204 | 409 = 204
  await installEventSource(context)
  await routePublic(context, () => claimStatus)
  await routeAdmin(context)
  const runtime: Runtime[] = []
  for (const viewport of viewports) {
    for (const state of ["progressive", "denial", "old-replaced"] as const) {
      claimStatus = state === "denial" ? 409 : 204
      const page = await context.newPage()
      await page.setViewportSize(viewport)
      runtime.push(await capturePublic(page, state, viewport.label))
    }
    const adminPage = await context.newPage()
    await adminPage.setViewportSize(viewport)
    runtime.push(await captureAdmin(adminPage, viewport.label))
  }

  const captures = runtime.map(({ scenarioId, viewport }) => {
    const filename = scenarioId.startsWith("admin-")
      ? `visual-final-admin-confirm-${viewport.width}x${viewport.height}.png`
      : scenarioId.includes("old-replaced")
        ? `visual-final-old-replaced-${viewport.width}x${viewport.height}.png`
      : `visual-final-${scenarioId
        .replace("public-display-", "public-")
        .replace(`-${viewport.width}x${viewport.height}`, "")}-${viewport.width}x${viewport.height}.png`
    return { filename, scenarioId, viewport }
  })
  const hashed = []
  for (const capture of captures) {
    const artifact = path.join(evidenceDir, capture.filename)
    const contents = await readFile(artifact)
    const fileStat = await stat(artifact)
    expect(contents.subarray(0, 8).toString("hex")).toBe("89504e470d0a1a0a")
    expect(fileStat.size).toBeGreaterThan(0)
    hashed.push({
      artifact: path.relative(path.resolve(".."), artifact),
      bytes: fileStat.size,
      scenarioId: capture.scenarioId,
      sha256: createHash("sha256").update(contents).digest("hex"),
      viewport: capture.viewport,
    })
  }
  await writeFile(path.join(evidenceDir, "visual-final-runtime.json"), `${JSON.stringify(runtime, null, 2)}\n`)
  await writeFile(path.join(evidenceDir, "visual-final-manifest.json"), `${JSON.stringify({
    browser: "Playwright Chromium via @playwright/test",
    captures: hashed,
    generatedAt: new Date().toISOString(),
    runtime,
  }, null, 2)}\n`)
  expect(hashed).toHaveLength(12)
})
