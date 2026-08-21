import { expect, test, type Locator, type Page, type Route } from "@playwright/test"
import { createHash } from "node:crypto"
import { mkdir, readFile, writeFile } from "node:fs/promises"
import path from "node:path"
import { z } from "zod"

const boardId = "11111111-1111-4111-8111-111111111111"
const evidenceDir = process.env.TASK22_EVIDENCE_DIR ?? path.resolve("../.omo/evidence/task22-admin-board-ui")
const now = "2026-08-21T00:00:00.000Z"
const board = {
  canvasHeight: 1080,
  canvasWidth: 1920,
  createdAt: now,
  id: boardId,
  shareLinkVersion: 1,
  status: "설정 중",
  title: "가을 서명 발표회",
  updatedAt: now,
} as const
const rosterRowsRequestSchema = z.strictObject({
  rows: z.array(z.strictObject({ job: z.string(), name: z.string(), organization: z.string() })),
})
type ElementBounds = {
  readonly height: number
  readonly width: number
  readonly x: number
  readonly y: number
}
type ViewportSize = { readonly height: number; readonly width: number }
type TopElementEvidence = {
  readonly header: ElementBounds | null
  readonly heading: ElementBounds | null
  readonly navigation: ElementBounds | null
}
type TopElementRequirements = {
  readonly header: boolean
  readonly navigation: boolean
  readonly preserveFocus?: boolean
}
type CaptureMode =
  | { readonly control: Locator; readonly kind: "focused" }
  | { readonly kind: "top"; readonly preserveFocus: boolean }
type CaptureRequest = {
  readonly mode?: CaptureMode
  readonly route: string
  readonly state: string
  readonly viewport: ViewportSize
}

function expectInViewport(label: string, bounds: ElementBounds | null, viewport: ViewportSize): ElementBounds {
  if (bounds === null) {
    throw new Error(`Expected ${label} layout bounds`)
  }
  expect(bounds.x, `${label} left edge`).toBeGreaterThanOrEqual(0)
  expect(bounds.y, `${label} top edge`).toBeGreaterThanOrEqual(0)
  expect(bounds.x + bounds.width, `${label} right edge`).toBeLessThanOrEqual(viewport.width)
  expect(bounds.y + bounds.height, `${label} bottom edge`).toBeLessThanOrEqual(viewport.height)
  return bounds
}

async function collectTopElementEvidence(
  page: Page,
  viewport: ViewportSize,
  requirements: TopElementRequirements,
): Promise<TopElementEvidence> {
  const preserveFocus = requirements.preserveFocus ?? false
  await page.evaluate((shouldPreserveFocus) => {
    if (!shouldPreserveFocus && document.activeElement instanceof HTMLElement) {
      document.activeElement.blur()
    }
    window.scrollTo(0, 0)
  }, preserveFocus)
  await page.waitForFunction(() => window.scrollY === 0)
  await page.evaluate(async () => {
    await document.fonts.ready
    await new Promise<void>((resolve) => {
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
    })
  })

  const heading = page.getByRole("heading", { level: 1 }).first()
  await expect(heading).toBeVisible()
  const headingBounds = expectInViewport("h1", await heading.boundingBox(), viewport)

  const header = page.locator(".app-header").first()
  const headerCount = await header.count()
  expect(headerCount).toBe(requirements.header ? 1 : 0)
  const headerBounds = requirements.header
    ? expectInViewport("header", await header.boundingBox(), viewport)
    : null

  const navigation = page.getByRole("navigation", { name: "관리자 세션" })
  const navigationCount = await navigation.count()
  expect(navigationCount).toBe(requirements.navigation ? 1 : 0)
  const navigationBounds = requirements.navigation
    ? expectInViewport("navigation", await navigation.boundingBox(), viewport)
    : null
  if (requirements.navigation) {
    await expect(header.locator('nav[aria-label="관리자 세션"]')).toHaveCount(1)
  }
  expect(await page.evaluate(() => window.scrollY)).toBe(0)
  expect(await page.evaluate(() => window.visualViewport?.pageTop ?? window.scrollY)).toBe(0)

  return { header: headerBounds, heading: headingBounds, navigation: navigationBounds }
}

async function collectStickyHeaderEvidence(page: Page, viewport: ViewportSize): Promise<TopElementEvidence> {
  const header = page.locator(".app-header").first()
  const navigation = page.getByRole("navigation", { name: "관리자 세션" })
  await expect(header).toHaveCSS("position", "sticky")
  const headerBounds = expectInViewport("sticky header", await header.boundingBox(), viewport)
  expect(headerBounds.y).toBe(0)
  const navigationBounds = expectInViewport("sticky navigation", await navigation.boundingBox(), viewport)
  await expect(header.locator('nav[aria-label="관리자 세션"]')).toHaveCount(1)
  return { header: headerBounds, heading: null, navigation: navigationBounds }
}

test("creates a board and keeps the server roster snapshot through import errors", async ({ context, page }) => {
  // Given
  let boards: (typeof board)[] = []
  let malformedBoards = false
  let rejectCreate = false
  let rejectImport = false
  const pendingCreateRoutes: Route[] = []
  let roster: readonly Record<string, unknown>[] = []
  const browserErrors: string[] = []
  const expectedNetworkErrors: string[] = []
  page.on("console", (message) => {
    if (message.type() === "error") {
      if (/^Failed to load resource: the server responded with a status of (400 \(Bad Request\)|409 \(Conflict\))$/u.test(message.text())) {
        expectedNetworkErrors.push(message.text())
      } else {
        browserErrors.push(message.text())
      }
    }
  })
  page.on("pageerror", (error) => browserErrors.push(error.message))
  await context.addCookies([{ name: "XSRF-TOKEN", value: "csrf-test", url: "http://127.0.0.1:4173" }])
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === "/api/v1/auth/session") {
      await route.fulfill({ json: { authenticated: true, expiresAt: new Date(Date.now() + 3_600_000).toISOString() } })
      return
    }
    if (pathname === "/api/v1/admin/boards" && request.method() === "GET") {
      await route.fulfill({
        contentType: "application/json",
        body: malformedBoards ? "{malformed" : JSON.stringify(boards),
      })
      return
    }
    if (pathname === "/api/v1/admin/boards" && request.method() === "POST") {
      if (rejectCreate) {
        pendingCreateRoutes.push(route)
        return
      }
      expect(request.postDataJSON()).toEqual({ title: "가을 서명 발표회" })
      boards = [board]
      await route.fulfill({ json: { board, shareToken: "never-render-this-token" } })
      return
    }
    if (pathname === `/api/v1/admin/boards/${boardId}`) {
      await route.fulfill({
        json: request.headers().referer?.endsWith(`/boards/${boardId}/full`) ? { status: "ready" } : board,
      })
      return
    }
    if (pathname === "/api/v1/public/sign/safe-share") {
      await route.fulfill({ json: { status: "ready" } })
      return
    }
    if (pathname === `/api/v1/admin/boards/${boardId}/roster` && request.method() === "GET") {
      await route.fulfill({ json: roster })
      return
    }
    if (pathname === `/api/v1/admin/boards/${boardId}/roster` && request.method() === "PUT") {
      const rows = rosterRowsRequestSchema.parse(request.postDataJSON()).rows
      roster = rows.map((identity, index) => ({
        id: `00000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
        identity,
        slot: {
          backgroundColor: "TRANSPARENT",
          height: null,
          id: `10000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
          placementStatus: "UNPLACED",
          revision: 0,
          width: null,
          x: null,
          y: null,
        },
        submitted: false,
      }))
      await route.fulfill({ json: roster })
      return
    }
    if (pathname.endsWith("/roster/import")) {
      if (rejectImport) {
        await route.fulfill({
          json: {
            errors: [
              { code: "DUPLICATE_IDENTITY", row: 2 },
              { code: "BLANK_NAME", row: 3 },
            ],
          },
          status: 400,
        })
      } else {
        await route.fulfill({ json: roster })
      }
      return
    }
    await route.fulfill({ json: { code: "NOT_FOUND" }, status: 404 })
  })

  // When
  await page.goto("/boards")
  await expect(page.getByText("아직 만든 보드가 없습니다.")).toBeVisible()
  await page.getByRole("link", { name: "새 보드 만들기" }).click()
  await page.getByLabel("보드 제목").fill("가을 서명 발표회")
  await page.getByRole("button", { name: "보드 만들기" }).click()
  const pastedRows = Array.from({ length: 25 }, (_, index) =>
    `나래초\t교사\t서명자${String(index + 1).padStart(2, "0")}`).join("\n")
  await page.getByLabel("소속, 직책, 이름 순서로 붙여넣기").fill(pastedRows)
  await page.getByRole("button", { name: "붙여넣기 미리보기" }).click()
  await expect(page.getByText("25명 미리보기")).toBeVisible()
  await page.getByRole("button", { name: "붙여넣기 적용" }).click()
  await expect(page.locator('input[name="name"]').last()).toHaveValue("서명자25")
  const initialFileInput = page.getByLabel("CSV 또는 XLSX 파일 선택")
  await initialFileInput.setInputFiles({
    buffer: Buffer.from("나래초,교사,서명자01\n별빛초,교사,서명자02"),
    mimeType: "text/csv",
    name: "roster.csv",
  })
  await expect(initialFileInput).toHaveCSS("opacity", "0")
  await expect(page.getByText("roster.csv")).toBeVisible()
  await page.getByRole("button", { name: "파일 가져오기" }).click()

  // Then
  await expect(page.locator('input[name="name"]').last()).toHaveValue("서명자25")
  const initialViewport = page.viewportSize()
  if (initialViewport === null) {
    throw new Error("Expected initial viewport size")
  }
  await collectTopElementEvidence(page, initialViewport, { header: true, navigation: true })
  await page.screenshot({ path: path.join(evidenceDir, "task-22-admin-board-ui.png"), fullPage: true })

  // Given
  rejectImport = true

  // When
  await page.getByLabel("CSV 또는 XLSX 파일 선택").setInputFiles({
    buffer: Buffer.from("나래초,교사,서명자01\n별빛초,교사,서명자02"),
    mimeType: "text/csv",
    name: "rejected.csv",
  })
  await page.getByRole("button", { name: "파일 가져오기" }).click()

  // Then
  await expect(page.getByRole("alert", { name: "명단 유지 안내" })).toContainText("서버 명단은")
  const serverErrors = page.getByRole("alert", { name: "서버 입력 오류" })
  await expect(serverErrors).toContainText("2행: 같은 명단이 중복되었습니다.")
  await expect(serverErrors).toContainText("3행: 이름을 입력해 주세요.")
  await expect(page.locator('input[name="name"]').last()).toHaveValue("서명자25")
  await expect(serverErrors.locator("li").nth(0)).toHaveAttribute("data-row", "2")
  await expect(serverErrors.locator("li").nth(1)).toHaveAttribute("data-row", "3")
  await collectTopElementEvidence(page, initialViewport, { header: true, navigation: true })
  await page.screenshot({ path: path.join(evidenceDir, "task-22-admin-board-ui-error.png"), fullPage: true })

  // Given
  malformedBoards = true

  // When
  await page.getByRole("link", { name: "보드 목록" }).click()
  await page.getByRole("button", { name: "목록 새로고침" }).click()

  // Then
  await expect(page.getByRole("alert")).toContainText("보드 목록을 불러오지 못했습니다")
  await page.getByRole("button", { name: "다시 불러오기" }).click()
  await expect(page.getByRole("alert")).toContainText("보드 목록을 불러오지 못했습니다")
  await expect(page.locator("body")).not.toContainText("never-render-this-token")
  await expect(page.locator("body")).not.toContainText("DUPLICATE_IDENTITY")

  // Given: a complete deterministic visual matrix for every required viewport.
  const matrixDir = path.join(evidenceDir, "matrix")
  await mkdir(matrixDir, { recursive: true })
  const matrix: {
    file: string
    focusedControl: ElementBounds | null
    pageTop: boolean
    route: string
    scrollY: number
    state: string
    topElements: TopElementEvidence
    viewport: { height: number; width: number }
  }[] = []
  const captureHashes = new Map<string, string>()
  const viewports = [
    { height: 812, width: 375 },
    { height: 1024, width: 768 },
    { height: 800, width: 1280 },
  ] as const
  const capture = async ({
    mode = { kind: "top", preserveFocus: false },
    route,
    state,
    viewport,
  }: CaptureRequest) => {
    const file = `${state}-${viewport.width}.png`
    const isUnsupportedPhone = state === "public-signer-handoff" && viewport.width === 375
    const isAdminRoute = route.startsWith("/boards")
    const topElements = mode.kind === "top"
      ? await collectTopElementEvidence(page, viewport, {
          header: !isUnsupportedPhone,
          navigation: isAdminRoute,
          preserveFocus: mode.preserveFocus,
        })
      : await collectStickyHeaderEvidence(page, viewport)
    const focusedControl = mode.kind === "focused"
      ? expectInViewport("focused control", await mode.control.boundingBox(), viewport)
      : null
    const scrollY = await page.evaluate(() => window.scrollY)
    const pageTop = scrollY === 0 && await page.evaluate(() => (window.visualViewport?.pageTop ?? window.scrollY) === 0)
    expect(pageTop).toBe(mode.kind === "top")
    if (mode.kind === "focused") {
      expect(scrollY).toBeGreaterThan(0)
      await expect(mode.control).toBeFocused()
    }
    const capturePath = path.join(matrixDir, file)
    await page.screenshot({ path: capturePath })
    const hash = createHash("sha256").update(await readFile(capturePath)).digest("hex")
    expect(captureHashes.has(hash), `${file} duplicates ${captureHashes.get(hash) ?? "another capture"}`).toBe(false)
    captureHashes.set(hash, file)
    matrix.push({ file, focusedControl, pageTop, route, scrollY, state, topElements, viewport })
  }

  for (const viewport of viewports) {
    await page.setViewportSize(viewport)

    boards = []
    malformedBoards = false
    await page.goto("/boards")
    await expect(page.getByText("아직 만든 보드가 없습니다.")).toBeVisible()
    const createLink = page.getByRole("link", { name: "새 보드 만들기" })
    await expect(createLink).toHaveCSS("border-radius", "8px")
    await expect(createLink).toHaveCSS("background-color", "rgb(0, 107, 255)")
    const logoutButton = page.getByRole("navigation", { name: "관리자 세션" }).getByRole("button", {
      name: "로그아웃",
    })
    await logoutButton.focus()
    await expect(logoutButton).toHaveCSS("outline-style", "solid")
    await logoutButton.blur()
    await capture({ route: "/boards", state: "list-empty", viewport })

    boards = [board]
    await page.goto("/boards")
    await expect(page.getByRole("heading", { name: board.title })).toBeVisible()
    await capture({ route: "/boards", state: "list-populated", viewport })

    malformedBoards = true
    await page.goto("/boards")
    await expect(page.getByRole("alert")).toContainText("보드 목록을 불러오지 못했습니다")
    await capture({ route: "/boards", state: "list-generic-error", viewport })
    malformedBoards = false

    await page.goto("/boards/new")
    await expect(page.getByRole("button", { name: "보드 만들기" })).toBeVisible()
    await capture({ route: "/boards/new", state: "create-default", viewport })

    rejectCreate = true
    await page.getByLabel("보드 제목").fill("오류 검증 보드")
    await page.getByRole("button", { name: "보드 만들기" }).click()
    const pendingCreateButton = page.getByRole("button", { name: "만드는 중" })
    await expect(pendingCreateButton).toBeDisabled()
    await expect(pendingCreateButton).toHaveCSS("background-color", "rgb(240, 243, 248)")
    await expect(pendingCreateButton).toHaveCSS("color", "rgb(71, 103, 136)")
    await capture({ route: "/boards/new", state: "create-pending", viewport })
    const pendingCreateRoute = pendingCreateRoutes.shift()
    if (pendingCreateRoute === undefined) {
      throw new Error("Expected a pending create route")
    }
    await pendingCreateRoute.fulfill({ json: { code: "BOARD_TITLE_DUPLICATE" }, status: 409 })
    await expect(page.getByRole("alert")).toContainText("보드를 만들지 못했습니다")
    await capture({ route: "/boards/new", state: "create-error", viewport })
    rejectCreate = false

    await page.goto(`/boards/${boardId}/edit`)
    await expect(page.locator('input[name="name"]').last()).toHaveValue("서명자25")
    await capture({ route: `/boards/${boardId}/edit`, state: "roster-happy", viewport })

    await page.getByLabel("CSV 또는 XLSX 파일 선택").setInputFiles({
      buffer: Buffer.from("나래초,교사,서명자01\n별빛초,교사,서명자02"),
      mimeType: "text/csv",
      name: "rejected.csv",
    })
    await expect(page.getByText("rejected.csv")).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.getByRole("button", { name: "파일 가져오기" }).click()
    await expect(page.getByRole("alert", { name: "서버 입력 오류" })).toContainText("2행: 같은 명단이 중복되었습니다.")
    await expect(page.locator('input[name="name"]').last()).toHaveValue("서명자25")
    const keptPhrase = page.locator(".board-keep-together")
    await expect(keptPhrase).toHaveText("서버 명단은")
    expect(await keptPhrase.evaluate((element) => element.getClientRects().length)).toBe(1)
    await capture({ route: `/boards/${boardId}/edit`, state: "roster-server-error", viewport })

    const textarea = page.getByLabel("소속, 직책, 이름 순서로 붙여넣기")
    await textarea.scrollIntoViewIfNeeded()
    await textarea.focus()
    await expect(textarea).toBeFocused()
    await expect(textarea).toHaveCSS("outline-style", "solid")
    const textareaBox = await textarea.boundingBox()
    if (textareaBox === null) {
      throw new Error("Expected textarea layout bounds")
    }
    expect(textareaBox.x + textareaBox.width).toBeLessThanOrEqual(viewport.width)
    await capture({
      mode: { control: textarea, kind: "focused" },
      route: `/boards/${boardId}/edit`,
      state: "roster-textarea-focus",
      viewport,
    })
    await expect(page.getByRole("heading", { level: 2, name: "명단 직접 추가" })).toHaveClass("board-section-title")
    await expect(page.getByRole("heading", { level: 2, name: "명단 가져오기" })).toHaveClass("board-section-title")

    const deleteButton = page.getByRole("button", { name: "삭제" }).first()
    await deleteButton.click()
    const dialog = page.getByRole("dialog", { name: "확인" })
    await expect(dialog).toContainText("선택한 명단을 삭제할까요?")
    expect(await dialog.evaluate((element) => element.matches(":modal"))).toBe(true)
    await expect(dialog.getByRole("button", { name: "취소" })).toBeFocused()
    expect(await page.locator('input[name="name"]').first().evaluate((element) => {
      if (!(element instanceof HTMLElement)) {
        return false
      }
      element.focus()
      return document.activeElement === element
    })).toBe(false)
    await page.keyboard.press("Escape")
    await expect(dialog).not.toBeVisible()
    await expect(deleteButton).toBeFocused()

    await deleteButton.click()
    await expect(dialog).toBeVisible()
    expect(await dialog.evaluate((element) => element.matches(":modal"))).toBe(true)
    await expect(dialog.getByRole("button", { name: "취소" })).toBeFocused()
    await expect(dialog.getByRole("button", { name: "삭제" })).toHaveCSS("background-color", "rgb(11, 53, 88)")
    await dialog.scrollIntoViewIfNeeded()
    const dialogBox = await dialog.boundingBox()
    if (dialogBox === null) {
      throw new Error("Expected confirmation layout bounds")
    }
    expect(dialogBox.x + dialogBox.width / 2).toBeCloseTo(viewport.width / 2, 0)
    await capture({
      mode: { kind: "top", preserveFocus: true },
      route: `/boards/${boardId}/edit`,
      state: "roster-delete-confirmation",
      viewport,
    })
    await dialog.getByRole("button", { name: "취소" }).click()
    await expect(dialog).not.toBeVisible()
    await expect(deleteButton).toBeFocused()

    await page.goto(`/boards/${boardId}/full`)
    await expect(page.getByRole("heading", { name: "보드 전체보기" })).toBeVisible()
    await expect(page.getByTestId("full-view-canvas")).toBeAttached()
    await capture({ route: `/boards/${boardId}/full`, state: "full-view-handoff", viewport })

    await page.goto("/sign/safe-share")
    if (viewport.width === 375) {
      await expect(page.getByTestId("unsupported-device-view")).toBeVisible()
    } else {
      await expect(page.getByTestId("signer-canvas")).toBeVisible()
    }
    await capture({ route: "/sign/safe-share", state: "public-signer-handoff", viewport })
  }

  await writeFile(path.join(evidenceDir, "matrix-manifest.json"), `${JSON.stringify({ captures: matrix }, null, 2)}\n`)
  expect(matrix).toHaveLength(36)
  expect(captureHashes.size).toBe(36)
  expect(expectedNetworkErrors).toHaveLength(7)
  expect(browserErrors).toEqual([])
})
