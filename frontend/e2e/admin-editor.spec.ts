import { expect, test, type Locator, type Page, type Route } from "@playwright/test"
import { mkdir, writeFile } from "node:fs/promises"
import path from "node:path"
import encodeQR, { Bitmap } from "qr"
import decodeQR from "qr/decode.js"

const boardId = "11111111-1111-4111-8111-111111111111"
const slotOne = "21111111-1111-4111-8111-111111111111", slotTwo = "22222222-2222-4222-8222-222222222222"
const evidenceDir = process.env.TASK23_EVIDENCE_DIR ?? path.resolve("../.omo/evidence/task-23-admin-canvas-ui/browser")
const now = "2026-08-21T00:00:00.000Z"

function rosterEntry(id: string, slotId: string, name: string) {
  return {
    id,
    identity: { job: "담당", name, organization: "나래" },
    slot: { backgroundColor: "transparent", height: null, id: slotId, placementStatus: "UNPLACED", revision: 0, width: null, x: null, y: null },
    submitted: false,
  }
}

async function syntheticPng(page: Page, first: string, second: string) {
  return Buffer.from(await page.evaluate(([start, end]) => {
    const canvas = document.createElement("canvas")
    canvas.width = 1200; canvas.height = 800
    const context = canvas.getContext("2d")
    if (context === null) throw new Error("Synthetic canvas unavailable")
    const gradient = context.createLinearGradient(0, 0, canvas.width, canvas.height)
    gradient.addColorStop(0, start); gradient.addColorStop(1, end)
    context.fillStyle = gradient; context.fillRect(0, 0, canvas.width, canvas.height)
    return canvas.toDataURL("image/png").split(",").at(1) ?? ""
  }, [first, second]), "base64")
}

type CaptureOptions = { readonly anchor?: Locator; readonly dialog?: boolean; readonly scrollOffset?: number }

async function captureState(page: Page, state: string, options: CaptureOptions = {}) {
  for (const width of [375, 768, 1280]) {
    const height = width === 1280 ? 800 : 812
    await page.setViewportSize({ width, height })
    if (options.anchor !== undefined) await options.anchor.scrollIntoViewIfNeeded()
    if (options.scrollOffset !== undefined) await page.evaluate((offset) => window.scrollBy(0, offset), options.scrollOffset)
    const metadata = await page.evaluate(() => ({
      captureMode: "viewport",
      documentHeight: document.documentElement.scrollHeight,
      focus: document.activeElement?.getAttribute("aria-label") ?? document.activeElement?.tagName ?? null,
      openDialogCount: document.querySelectorAll("dialog[open]").length,
      scrollX: window.scrollX,
      scrollY: window.scrollY,
      stickyHeaderCount: document.querySelectorAll(".app-header").length,
      stickyHeaderTop: document.querySelector(".app-header")?.getBoundingClientRect().top ?? null,
    }))
    expect(metadata.stickyHeaderCount).toBe(1)
    expect(metadata.stickyHeaderTop).toBe(0)
    expect(metadata.openDialogCount).toBe(options.dialog === true ? 1 : 0)
    if (state === "authoritative-409-recovery" && width === 375) expect(await page.locator(".slot-overlay").evaluateAll((slots) => slots.every((slot) => { const name = slot.querySelector(".slot-move"), buttons = [...slot.querySelectorAll("button")]; return name !== null && name.scrollWidth <= name.clientWidth && buttons.every((button, index) => buttons.slice(index + 1).every((other) => { const first = button.getBoundingClientRect(), second = other.getBoundingClientRect(); return first.right <= second.left || second.right <= first.left || first.bottom <= second.top || second.bottom <= first.top })) }))).toBe(true)
    const stem = `${state}-${width}`
    await page.screenshot({ fullPage: false, path: path.join(evidenceDir, `${stem}.png`) })
    await writeFile(path.join(evidenceDir, `${stem}.json`), `${JSON.stringify({ height, state, viewport: width, ...metadata }, null, 2)}\n`)
  }
  await page.setViewportSize({ width: 1280, height: 800 })
}

test("@admin-editor edits the authoritative canvas and recovers failed mutations", async ({ context, page }) => {
  // Given
  await mkdir(evidenceDir, { recursive: true })
  await context.grantPermissions(["clipboard-read", "clipboard-write"])
  await context.addCookies([{ name: "XSRF-TOKEN", value: "csrf-redacted", url: "http://127.0.0.1:4173" }])
  let board = { canvasHeight: 1080, canvasWidth: 1920, createdAt: now, id: boardId, shareLinkVersion: 1, status: "설정 중", title: "가을 서명 발표회", updatedAt: now }
  let roster = [
    rosterEntry("31111111-1111-4111-8111-111111111111", slotOne, "한별"),
    rosterEntry("32222222-2222-4222-8222-222222222222", slotTwo, "누리"),
  ]
  let share = { shareToken: "safe-share-one", version: 1 }
  let currentBackground = await syntheticPng(page, "seagreen", "midnightblue"); const replacementBackground = await syntheticPng(page, "royalblue", "goldenrod")
  let patchCount = 0, backgroundCount = 0, backgroundGets = 0, latestPatchX = 0
  const latestPatchBySlot = new Map<string, { readonly x: number; readonly y: number }>()
  let rejectNextBackgroundRead = false, rejectNextPatch = false, rejectNextBackground = true
  let holdNextPatch = false, holdNextSuccessfulPatch = false, holdNextRoster = false
  let recoveryStarted = false
  let releaseFirstPatch = () => undefined, releaseRejectedPatch = () => undefined, releaseSuccessfulPatch = () => undefined, releaseRecovery = () => undefined
  const firstPatchGate = new Promise<void>((resolve) => { releaseFirstPatch = resolve }), rejectedPatchGate = new Promise<void>((resolve) => { releaseRejectedPatch = resolve })
  const successfulPatchGate = new Promise<void>((resolve) => { releaseSuccessfulPatch = resolve }), recoveryGate = new Promise<void>((resolve) => { releaseRecovery = resolve })
  const browserErrors: string[] = []
  page.on("pageerror", (error) => browserErrors.push(error.message))
  page.on("console", (message) => {
    if (message.type() === "error" && !message.text().includes("409 (Conflict)") && !message.text().includes("400 (Bad Request)") && !message.text().includes("404 (Not Found)")) browserErrors.push(message.text())
  })
  await page.route("**/api/v1/**", async (route: Route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === "/api/v1/auth/session") return route.fulfill({ json: { authenticated: true, expiresAt: new Date(Date.now() + 3_600_000).toISOString() } })
    if (pathname === `/api/v1/admin/boards/${boardId}` && request.method() === "GET") return route.fulfill({ json: board })
    if (pathname === `/api/v1/admin/boards/${boardId}/roster`) {
      if (holdNextRoster) { recoveryStarted = true; holdNextRoster = false; await recoveryGate }
      return route.fulfill({ json: roster })
    }
    if (pathname === `/api/v1/admin/boards/${boardId}/share` && request.method() === "GET") return route.fulfill({ json: share })
    if (pathname.endsWith("/share/reissue")) { share = { shareToken: "safe-share-two", version: 2 }; return route.fulfill({ json: share }) }
    if (pathname.endsWith("/background")) {
      if (request.method() === "GET") {
        backgroundGets += 1
        if (rejectNextBackgroundRead) { rejectNextBackgroundRead = false; return route.fulfill({ status: 404, json: { code: "OBJECT_KEY_MISSING", objectKey: "private/owner/board.png" } }) }
        return route.fulfill({ body: currentBackground, contentType: "image/png" })
      }
      backgroundCount += 1
      if (rejectNextBackground) { rejectNextBackground = false; return route.fulfill({ status: 400, json: { code: "BACKGROUND_INVALID" } }) }
      if (request.postData()?.includes('name="adoptSourceRatio"\r\n\r\ntrue') === true) board = { ...board, canvasHeight: 1200 }
      currentBackground = replacementBackground
      return route.fulfill({ json: { displayHeight: board.canvasHeight, displayWidth: board.canvasWidth, id: "41111111-1111-4111-8111-111111111111", mimeType: "image/png" } })
    }
    if (/\/slots\//u.test(pathname) && request.method() === "PATCH") {
      patchCount += 1
      const body = request.postDataJSON(), slotId = pathname.split("/").at(-1)
      if (slotId === undefined) throw new Error("Missing slot id")
      latestPatchX = body.x; latestPatchBySlot.set(slotId, { x: body.x, y: body.y })
      if (patchCount === 1) await firstPatchGate
      if (holdNextPatch) { holdNextPatch = false; await rejectedPatchGate }
      if (rejectNextPatch) { rejectNextPatch = false; return route.fulfill({ status: 409, json: { code: "SLOT_OVERLAP" } }) }
      if (holdNextSuccessfulPatch) { holdNextSuccessfulPatch = false; await successfulPatchGate }
      roster = roster.map((entry) => entry.slot.id === slotId ? { ...entry, slot: {
        ...entry.slot,
        backgroundColor: body.background,
        height: body.height,
        placementStatus: "PLACED",
        revision: entry.slot.revision + 1,
        width: body.width,
        x: body.x,
        y: body.y,
      } } : entry)
      const entry = roster.find((candidate) => candidate.slot.id === slotId)
      if (entry === undefined) return route.fulfill({ status: 404, json: { code: "NOT_FOUND" } })
      return route.fulfill({ json: { background: String(entry.slot.backgroundColor).toUpperCase(), boardId, bounds: { height: entry.slot.height, width: entry.slot.width, x: entry.slot.x, y: entry.slot.y }, id: entry.slot.id, revision: entry.slot.revision, rosterEntryId: entry.id, signaturePresent: false, submitted: false } })
    }
    if (/\/slots\//u.test(pathname) && request.method() === "DELETE") return route.fulfill({ status: 204 })
    if (/\/(open|close|reopen)$/u.test(pathname)) {
      board = { ...board, status: pathname.endsWith("/close") ? "마감/보관" : "서명 진행" }
      return route.fulfill({ json: board })
    }
    if (pathname === `/api/v1/admin/boards/${boardId}` && request.method() === "PATCH") {
      const body = request.postDataJSON()
      board = { ...board, title: typeof body.title === "string" ? body.title : board.title }
      return route.fulfill({ json: board })
    }
    return route.fulfill({ status: 404, json: { code: "NOT_FOUND" } })
  })

  await page.setViewportSize({ width: 1280, height: 800 })
  await page.goto(`/boards/${boardId}/edit`)
  const canvas = page.getByRole("application", { name: "서명 보드 캔버스" })
  const backgroundPanel = page.getByRole("heading", { name: "배경" })
  const sharePanel = page.getByRole("heading", { name: "공유" })
  const toolbar = page.getByLabel("자동 저장 상태")
  await expect(canvas.locator("img.editor-canvas-background")).toBeVisible()
  await captureState(page, "default-current-background-load", { anchor: canvas })
  await page.reload()
  await expect.poll(() => backgroundGets).toBeGreaterThanOrEqual(2)
  await expect(canvas.locator("img.editor-canvas-background")).toBeVisible()
  await captureState(page, "current-background-reload", { anchor: canvas, scrollOffset: 1 })
  rejectNextBackgroundRead = true; await page.reload()
  const backgroundFailure = page.getByRole("alert").filter({ hasText: "배경을 불러오지 못했습니다" })
  await expect(backgroundFailure).toBeVisible(); await expect(backgroundFailure).not.toContainText("OBJECT_KEY_MISSING"); await expect(backgroundFailure).not.toContainText("private/owner/board.png")
  await expect(page.getByLabel("PNG 또는 JPEG")).toBeDisabled(); await captureState(page, "background-load-failure", { anchor: backgroundFailure })
  await page.getByRole("button", { name: "배경 다시 불러오기" }).click(); await expect(canvas.locator("img.editor-canvas-background")).toBeVisible()
  await captureState(page, "background-load-retry-recovered", { anchor: canvas, scrollOffset: 2 })
  expect(await page.locator(".board-editor button").evaluateAll((buttons) => buttons.filter((button) => !button.classList.contains("board-button") && button.closest("dialog") === null).length)).toBe(0)
  const keyboardPlacement = page.getByRole("button", { name: "한별 배치" })
  await keyboardPlacement.focus(); await page.keyboard.press("Tab"); await page.keyboard.press("Shift+Tab"); await expect(keyboardPlacement).toBeFocused()
  const focusMetrics = await keyboardPlacement.evaluate((element) => { const style = getComputedStyle(element), root = getComputedStyle(document.documentElement), bounds = element.getBoundingClientRect(), width = Number.parseFloat(style.outlineWidth), offset = Number.parseFloat(style.outlineOffset), focusWidthValue = root.getPropertyValue("--focus-width").trim(), focusWidthPixels = focusWidthValue.endsWith("rem") ? Number.parseFloat(focusWidthValue) * Number.parseFloat(root.fontSize) : Number.parseFloat(focusWidthValue); return { outlineStyle: style.outlineStyle, outlineWidth: width, tokenWidth: focusWidthPixels, visibleBounds: bounds.left - width - offset >= 0 && bounds.right + width + offset <= innerWidth && bounds.top - width - offset >= 0 && bounds.bottom + width + offset <= innerHeight } })
  expect(focusMetrics).toMatchObject({ outlineStyle: "solid", outlineWidth: focusMetrics.tokenWidth, visibleBounds: true })
  await captureState(page, "keyboard-placement-focus", { anchor: keyboardPlacement })
  await keyboardPlacement.press("Enter")
  await expect.poll(() => patchCount).toBe(1)
  releaseFirstPatch()
  await expect(page.getByRole("button", { name: "한별 이동" })).toBeVisible()
  await captureState(page, "keyboard-placed", { anchor: canvas })
  const canvasBox = await canvas.boundingBox()
  if (canvasBox === null) throw new Error("Canvas bounds unavailable")
  await page.getByRole("button", { name: "누리 배치" }).dragTo(canvas, { targetPosition: { x: canvasBox.width * 0.24, y: 0 } })
  await expect.poll(() => patchCount).toBe(2)
  await expect(page.getByLabel("자동 저장 상태")).toHaveText("저장됨")
  await captureState(page, "drag-touching-edge", { anchor: canvas })
  await page.getByRole("button", { name: "누리 크기 조절" }).press("Shift+ArrowRight")
  await expect.poll(() => patchCount).toBe(3)
  await captureState(page, "resize-visible-handle", { anchor: canvas })

  const moveButton = page.getByRole("button", { name: "한별 이동" }), movingSlot = page.getByTestId(`slot-${slotOne}`)
  const secondMoveButton = page.getByRole("button", { name: "누리 이동" }), secondMovingSlot = page.getByTestId(`slot-${slotTwo}`)
  await moveButton.press("ArrowRight"); await moveButton.press("ArrowRight"); await secondMoveButton.press("ArrowDown")
  const firstPreviewX = Number.parseFloat(await movingSlot.evaluate((element) => element.style.left)) / 100, secondPreviewY = Number.parseFloat(await secondMovingSlot.evaluate((element) => element.style.top)) / 100
  expect(patchCount).toBe(3); await expect.poll(() => patchCount).toBe(5)
  expect(latestPatchBySlot.get(slotOne)?.x).toBeCloseTo(firstPreviewX); expect(latestPatchBySlot.get(slotTwo)?.y).toBeCloseTo(secondPreviewY)

  await page.getByRole("button", { name: "링크 복사" }).click()
  const copied = await page.evaluate(() => navigator.clipboard.readText())
  const bits = encodeQR(copied, "raw", { ecc: "medium" })
  expect(decodeQR(new Bitmap({ height: bits.length, width: bits[0]?.length ?? 0 }, bits).scale(4).toImage())).toBe(copied)
  await page.getByRole("button", { name: "링크 재발급" }).click()
  await expect(page.getByText(/기존 링크는 즉시 무효화/u)).toBeVisible()
  await captureState(page, "share-reissue-confirmation", { dialog: true })
  await page.getByRole("button", { name: "취소" }).click()
  await page.getByRole("button", { name: "링크 재발급" }).click()
  await page.getByRole("button", { exact: true, name: "재발급" }).click()
  await expect(page.locator("svg[data-share-url]")).toHaveAttribute("data-share-url", /safe-share-two/u)
  await captureState(page, "share-reissued", { anchor: sharePanel })
  await page.evaluate(() => Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: () => Promise.reject(new DOMException("denied")) } }))
  await page.getByRole("button", { name: "링크 복사" }).click()
  const copyFailure = page.getByText(/링크를 복사하지 못했습니다/u)
  await expect(copyFailure).toBeVisible()
  await captureState(page, "share-copy-failure", { anchor: copyFailure })

  rejectNextPatch = true; holdNextPatch = true; holdNextRoster = true
  await moveButton.press("ArrowRight"); await moveButton.press("ArrowRight"); await moveButton.press("ArrowLeft")
  const coalescedPreviewX = Number.parseFloat(await movingSlot.evaluate((element) => element.style.left)) / 100
  expect(patchCount).toBe(5)
  await expect.poll(() => patchCount).toBe(6)
  expect(latestPatchX).toBeCloseTo(coalescedPreviewX)
  await moveButton.press("ArrowLeft"); await moveButton.press("ArrowRight"); await moveButton.press("ArrowRight")
  await new Promise((resolve) => setTimeout(resolve, 100)); expect(patchCount).toBe(6)
  releaseRejectedPatch()
  await expect.poll(() => recoveryStarted).toBe(true)
  await new Promise((resolve) => setTimeout(resolve, 100)); expect(patchCount).toBe(6)
  holdNextSuccessfulPatch = true
  releaseRecovery()
  await expect(page.getByText(/서버 배치와 충돌/u)).toBeVisible()
  await expect.poll(() => patchCount).toBe(7)
  await expect(page.getByLabel("자동 저장 상태")).toHaveText("저장 실패")
  await captureState(page, "authoritative-409-recovery", { anchor: canvas })
  releaseSuccessfulPatch()
  await expect(page.getByLabel("자동 저장 상태")).toHaveText("저장됨")

  const upload = page.getByLabel("PNG 또는 JPEG")
  const currentBackgroundSrc = await canvas.locator("img.editor-canvas-background").getAttribute("src")
  await upload.setInputFiles({ buffer: replacementBackground, mimeType: "image/png", name: "synthetic.png" })
  await page.getByRole("button", { name: "기존 비율로 교체" }).click()
  await expect(page.getByText(/선택한 파일을 확인해 주세요/u)).toBeVisible()
  await expect(upload).toHaveJSProperty("files.length", 1)
  await expect(canvas.locator("img.editor-canvas-background")).toHaveAttribute("src", currentBackgroundSrc ?? "")
  await captureState(page, "upload-reject-retains-file", { anchor: backgroundPanel })
  await captureState(page, "upload-reject-retains-current-background", { anchor: canvas })
  await page.getByRole("button", { name: "이미지 비율 적용" }).click()
  await captureState(page, "ratio-adoption-cancel-confirmation", { dialog: true })
  await page.getByRole("button", { name: "취소" }).click()
  expect(backgroundCount).toBe(1)
  await page.getByRole("button", { name: "이미지 비율 적용" }).click()
  await page.getByRole("button", { exact: true, name: "비율 적용" }).click()
  await expect.poll(() => backgroundCount).toBe(2)
  await expect(canvas.locator("img.editor-canvas-background")).toBeVisible()
  await captureState(page, "ratio-adoption-confirmed-background", { anchor: canvas })

  await page.getByRole("button", { name: "서명 시작" }).click()
  await captureState(page, "lifecycle-open", { anchor: toolbar })
  await page.getByRole("button", { name: "마감" }).click()
  await captureState(page, "lifecycle-closed", { anchor: toolbar })
  await page.getByRole("button", { name: "다시 열기" }).click()
  await expect(page.getByText("서명 진행").first()).toBeVisible()
  await page.getByLabel("보드 제목").focus()
  await captureState(page, "lifecycle-reopened", { anchor: toolbar })
  const overflow = await page.locator("body *").evaluateAll((elements) => elements
    .filter((element) => element.getBoundingClientRect().right > window.innerWidth + 1)
    .map((element) => `${element.tagName}.${element.className}`))
  expect(overflow).toEqual([])
  expect(browserErrors).toEqual([])
})
