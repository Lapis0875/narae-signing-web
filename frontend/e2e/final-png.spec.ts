import { expect, test, type Page, type Route } from "@playwright/test"
import { mkdir, writeFile } from "node:fs/promises"
import path from "node:path"

const boardId = "11111111-1111-4111-8111-111111111111"
const evidenceDir = process.env.TASK27_EVIDENCE_DIR
  ?? path.resolve("../.omo/evidence/task-27-final-png/actual-editor")
const now = "2026-08-21T00:00:00.000Z"

async function backgroundPng(page: Page) {
  return Buffer.from(await page.evaluate(() => {
    const canvas = document.createElement("canvas")
    canvas.width = 1920
    canvas.height = 1080
    const context = canvas.getContext("2d")
    if (context === null) throw new Error("Synthetic canvas unavailable")
    context.fillStyle = "#f5f7fa"
    context.fillRect(0, 0, canvas.width, canvas.height)
    return canvas.toDataURL("image/png").split(",").at(1) ?? ""
  }), "base64")
}

async function settle(page: Page) {
  await page.evaluate(async () => {
    await document.fonts.ready
    await new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
  })
}

test("closed board exposes explicit final PNG retry in the production editor toolbar", async ({ context, page }) => {
  await mkdir(evidenceDir, { recursive: true })
  await context.addCookies([{ name: "XSRF-TOKEN", value: "csrf-redacted", url: "http://127.0.0.1:4173" }])
  const background = await backgroundPng(page)
  const browserErrors: string[] = []
  let finalPngRequests = 0
  page.on("pageerror", (error) => browserErrors.push(error.message))
  page.on("console", (message) => {
    if (message.type() === "error" && !message.text().includes("503 (Service Unavailable)")) {
      browserErrors.push(message.text())
    }
  })
  await page.route("**/api/v1/**", async (route: Route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === "/api/v1/auth/session") {
      return route.fulfill({ json: { authenticated: true, expiresAt: new Date(Date.now() + 3_600_000).toISOString() } })
    }
    if (pathname === `/api/v1/admin/boards/${boardId}`) {
      return route.fulfill({ json: {
        canvasHeight: 1080,
        canvasWidth: 1920,
        createdAt: now,
        id: boardId,
        shareLinkVersion: 1,
        status: "마감/보관",
        title: "완료된 서명 보드",
        updatedAt: now,
      } })
    }
    if (pathname === `/api/v1/admin/boards/${boardId}/roster`) return route.fulfill({ json: [] })
    if (pathname === `/api/v1/admin/boards/${boardId}/share`) {
      return route.fulfill({ json: { shareToken: "synthetic-share", version: 1 } })
    }
    if (pathname === `/api/v1/admin/boards/${boardId}/background`) {
      return route.fulfill({ body: background, contentType: "image/png" })
    }
    if (pathname === `/api/v1/admin/boards/${boardId}/final.png`) {
      finalPngRequests += 1
      return route.fulfill({
        body: '{"code":"FINAL_PNG_BUSY"}',
        contentType: "application/json",
        headers: { "Retry-After": "1" },
        status: 503,
      })
    }
    return route.fulfill({ status: 404, json: { code: "NOT_FOUND" } })
  })

  await page.goto(`/boards/${boardId}/edit`)
  const toolbar = page.locator(".editor-toolbar")
  const action = toolbar.getByRole("button", { name: "최종 PNG 다운로드" })
  await expect(page).toHaveURL(new RegExp(`/boards/${boardId}/edit$`, "u"))
  await expect(page.getByRole("heading", { level: 1, name: "완료된 서명 보드" })).toBeVisible()
  await expect(action).toBeVisible()
  await expect(action).toBeEnabled()

  for (const width of [375, 768, 1280]) {
    const height = width === 1280 ? 800 : 812
    await page.setViewportSize({ width, height })
    await toolbar.scrollIntoViewIfNeeded()
    await settle(page)
    const bounds = await action.boundingBox()
    expect(bounds).not.toBeNull()
    if (bounds !== null) {
      expect(bounds.x).toBeGreaterThanOrEqual(0)
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(width)
    }
    await page.screenshot({
      fullPage: false,
      path: path.join(evidenceDir, `actual-editor-rest-${width}.png`),
    })
    await writeFile(path.join(evidenceDir, `actual-editor-rest-${width}.json`), `${JSON.stringify({
      actionBounds: bounds,
      height,
      productionRoute: `/boards/${boardId}/edit`,
      toolbarCount: await page.locator(".editor-toolbar").count(),
      viewport: width,
    }, null, 2)}\n`)
  }

  await page.setViewportSize({ width: 375, height: 812 })
  await toolbar.scrollIntoViewIfNeeded()
  const reopen = toolbar.getByRole("button", { name: "다시 열기" })
  const restReopenBounds = await reopen.boundingBox()
  const restActionBounds = await action.boundingBox()
  expect(restReopenBounds).not.toBeNull()
  expect(restActionBounds).not.toBeNull()
  await action.focus()
  await expect(action).toBeFocused()
  await expect(action).toHaveCSS("outline-style", "solid")
  await page.screenshot({ fullPage: false, path: path.join(evidenceDir, "actual-editor-focus-375.png") })
  await action.click()
  await expect(page.getByRole("alert")).toContainText("다운로드하지 못했습니다")
  const finalPngGroup = toolbar.getByRole("group", { name: "최종 PNG 작업" })
  await expect(finalPngGroup).toBeVisible()
  const retry = toolbar.getByRole("button", { name: "다시 시도" })
  await expect(retry).toBeVisible()
  await page.waitForTimeout(250)
  expect(finalPngRequests).toBe(1)
  const errorReopenBounds = await reopen.boundingBox()
  const errorRetryBounds = await retry.boundingBox()
  const errorGroupBounds = await finalPngGroup.boundingBox()
  const toolbarBounds = await toolbar.boundingBox()
  const alertBounds = await page.getByRole("alert").boundingBox()
  expect(errorReopenBounds?.x).toBe(restReopenBounds?.x)
  expect(errorReopenBounds?.width).toBe(restReopenBounds?.width)
  expect(errorRetryBounds?.x).toBe(restActionBounds?.x)
  expect(errorGroupBounds).not.toBeNull()
  expect(toolbarBounds).not.toBeNull()
  expect(alertBounds).not.toBeNull()
  if (errorGroupBounds !== null && toolbarBounds !== null) {
    expect(errorGroupBounds.x).toBeGreaterThanOrEqual(toolbarBounds.x)
    expect(errorGroupBounds.x + errorGroupBounds.width).toBeLessThanOrEqual(toolbarBounds.x + toolbarBounds.width)
  }
  if (alertBounds !== null && errorReopenBounds !== null && errorRetryBounds !== null) {
    expect(alertBounds.y).toBeGreaterThanOrEqual(
      Math.max(errorReopenBounds.y + errorReopenBounds.height, errorRetryBounds.y + errorRetryBounds.height),
    )
  }
  expect(await page.evaluate(() => ({ scrollWidth: document.documentElement.scrollWidth, width: innerWidth })))
    .toEqual({ scrollWidth: 375, width: 375 })
  await page.screenshot({ fullPage: false, path: path.join(evidenceDir, "actual-editor-error-375.png") })
  expect(browserErrors).toEqual([])
})
