import { expect, test, type BrowserContext, type Page, type Route } from "@playwright/test"
import { readFileSync } from "node:fs"
import { z } from "zod"

const boardId = "20000000-0000-4000-8000-000000000001"
const rosterId = "30000000-0000-4000-8000-000000000001"
const slotId = "40000000-0000-4000-8000-000000000001"
const shareToken = "synthetic-task30-share"
const now = "2026-08-23T00:00:00.000Z"
const origin = "http://127.0.0.1:4173"
const identity = { job: "진행", name: "하늘", organization: "나래 테스트" } as const
const png = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
)

test.use({ screenshot: "off", trace: "off", video: "off" })

class Task30ConfigurationError extends Error {
  constructor(name: string) {
    super(`Task30 real mode requires ${name}`)
    this.name = "Task30ConfigurationError"
  }
}

function requiredEnvironment(name: string): string {
  const value = process.env[name]
  if (value === undefined || value.length === 0) throw new Task30ConfigurationError(name)
  return value
}

const liveCredentialSchema = z.strictObject({
  email: z.email(),
  password: z.string().min(15),
})

function readLiveCredentials(): z.infer<typeof liveCredentialSchema> {
  const fifo = requiredEnvironment("TASK30_CREDENTIAL_FIFO")
  return liveCredentialSchema.parse(JSON.parse(readFileSync(fifo, "utf8")))
}

type BoardStatus = "설정 중" | "서명 진행" | "마감/보관"
type RosterEntry = {
  readonly id: string
  readonly identity: typeof identity
  readonly slot: {
    readonly backgroundColor: string
    readonly height: number | null
    readonly id: string
    readonly placementStatus: "UNPLACED" | "PLACED"
    readonly revision: number
    readonly width: number | null
    readonly x: number | null
    readonly y: number | null
  }
  readonly submitted: boolean
}

// allow: SIZE_OK — one required mock contract and authorized live variant drive the same two-context event.
class MvpApi {
  private authenticated = false
  private backgroundPresent = false
  private boardCreated = false
  private boardStatus: BoardStatus = "설정 중"
  private deleted = false
  private roster: RosterEntry[] = []
  private draft = false
  private submitted = false
  private readonly eventWaiters: Array<() => void> = []
  readonly calls: string[] = []
  readonly unknown: string[] = []

  board() {
    return {
      canvasHeight: 600,
      canvasWidth: 800,
      createdAt: now,
      id: boardId,
      shareLinkVersion: 1,
      status: this.boardStatus,
      title: "통합 검증 행사",
      updatedAt: now,
    }
  }

  async handle(route: Route): Promise<void> {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    const key = `${request.method()} ${pathname}`
    this.calls.push(key)

    if (pathname === "/api/v1/auth/csrf") return route.fulfill({ status: 204 })
    if (pathname === "/api/v1/auth/session") {
      return route.fulfill({ json: this.authenticated
        ? { authenticated: true, expiresAt: new Date(Date.now() + 3_600_000).toISOString() }
        : { authenticated: false, expiresAt: null } })
    }
    if (key === "POST /api/v1/auth/login") {
      expect(request.postDataJSON()).toEqual({
        email: "task30@example.invalid",
        password: "synthetic-password-phrase",
      })
      this.authenticated = true
      return route.fulfill({ json: {
        authenticated: true,
        expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      } })
    }
    if (key === "GET /api/v1/admin/boards") {
      return route.fulfill({ json: this.boardCreated && !this.deleted ? [this.board()] : [] })
    }
    if (key === "POST /api/v1/admin/boards") {
      expect(request.postDataJSON()).toEqual({ title: "통합 검증 행사" })
      this.boardCreated = true
      return route.fulfill({ json: { board: this.board(), shareToken } })
    }
    if (pathname === `/api/v1/admin/boards/${boardId}` && request.method() === "GET") {
      return route.fulfill({ json: this.board() })
    }
    if (pathname === `/api/v1/admin/boards/${boardId}` && request.method() === "DELETE") {
      expect(request.postDataJSON()).toEqual({ confirmed: true })
      this.deleted = true
      return route.fulfill({ status: 204 })
    }
    if (key === `GET /api/v1/admin/boards/${boardId}/roster`) {
      return route.fulfill({ json: this.roster })
    }
    if (key === `POST /api/v1/admin/boards/${boardId}/roster`) {
      expect(request.postDataJSON()).toEqual(identity)
      this.roster = [{
        id: rosterId,
        identity,
        slot: {
          backgroundColor: "transparent",
          height: null,
          id: slotId,
          placementStatus: "UNPLACED",
          revision: 0,
          width: null,
          x: null,
          y: null,
        },
        submitted: false,
      }]
      return route.fulfill({ json: this.roster[0] })
    }
    if (key === `PATCH /api/v1/admin/boards/${boardId}/slots/${slotId}`) {
      const bounds = request.postDataJSON()
      expect(bounds).toEqual({ background: "transparent", height: 0.18, width: 0.24, x: 0, y: 0 })
      this.roster = [{
        ...this.roster[0],
        slot: {
          backgroundColor: "transparent",
          height: 0.18,
          id: slotId,
          placementStatus: "PLACED",
          revision: 1,
          width: 0.24,
          x: 0,
          y: 0,
        },
      }]
      return route.fulfill({ json: {
        background: "TRANSPARENT",
        boardId,
        bounds: { height: 0.18, width: 0.24, x: 0, y: 0 },
        id: slotId,
        revision: 1,
        rosterEntryId: rosterId,
        signaturePresent: false,
        submitted: false,
      } })
    }
    if (pathname === `/api/v1/admin/boards/${boardId}/background` && request.method() === "GET") {
      return this.backgroundPresent
        ? route.fulfill({ body: png, contentType: "image/png" })
        : route.fulfill({ status: 204 })
    }
    if (pathname === `/api/v1/admin/boards/${boardId}/background` && request.method() === "POST") {
      this.backgroundPresent = true
      return route.fulfill({ json: {
        displayHeight: 600,
        displayWidth: 800,
        id: "50000000-0000-4000-8000-000000000001",
        mimeType: "image/png",
      } })
    }
    if (key === `GET /api/v1/admin/boards/${boardId}/share`) {
      return route.fulfill({ json: { shareToken, version: 1 } })
    }
    if (key === `POST /api/v1/admin/boards/${boardId}/open`) {
      this.boardStatus = "서명 진행"
      return route.fulfill({ json: this.board() })
    }
    if (key === `POST /api/v1/admin/boards/${boardId}/close`) {
      this.boardStatus = "마감/보관"
      return route.fulfill({ json: this.board() })
    }
    if (key === `GET /api/v1/admin/boards/${boardId}/snapshot`) {
      return route.fulfill({ json: {
        backgroundPresent: this.backgroundPresent,
        boardId,
        canvasHeight: 600,
        canvasWidth: 800,
        slots: this.roster.map((entry) => ({
          background: "transparent",
          draftSignature: this.draft && !this.submitted
            ? { strokes: [{ points: [{ x: 100_000, y: 200_000 }, { x: 800_000, y: 700_000 }] }], version: 1 }
            : null,
          height: entry.slot.height,
          id: slotId,
          signature: this.submitted
            ? { strokes: [{ points: [{ x: 100_000, y: 200_000 }, { x: 800_000, y: 700_000 }] }], version: 1 }
            : null,
          width: entry.slot.width,
          x: entry.slot.x,
          y: entry.slot.y,
        })),
      } })
    }
    if (key === `GET /api/v1/admin/boards/${boardId}/events`) {
      if (!this.submitted) await new Promise<void>((resolve) => this.eventWaiters.push(resolve))
      return route.fulfill({ body: "event: signature-submitted\ndata: {}\n\n", contentType: "text/event-stream" })
    }
    if (key === `GET /api/v1/public/links/${shareToken}`) {
      return route.fulfill({ json: { state: this.boardStatus === "마감/보관" ? "CLOSED" : "OPEN", title: "통합 검증 행사" } })
    }
    if (key === `POST /api/v1/public/links/${shareToken}/identify`) {
      expect(request.postDataJSON()).toEqual(identity)
      return route.fulfill({ json: { identified: true } })
    }
    if (key === "GET /api/v1/public/signing-session") {
      const identified = this.calls.includes(`POST /api/v1/public/links/${shareToken}/identify`)
      if (!identified) return route.fulfill({ json: { code: "UNAUTHORIZED" }, status: 401 })
      return route.fulfill({ json: { signatureAspectRatio: 16 / 9, state: this.submitted ? "SUBMITTED" : "READY" } })
    }
    if (key === "PUT /api/v1/public/signing-session/draft") {
      expect(request.postDataJSON()).toMatchObject({ version: 1 })
      this.draft = true
      return route.fulfill({ status: 204 })
    }
    if (key === "POST /api/v1/public/signing-session/draft/clear") {
      this.draft = false
      return route.fulfill({ status: 204 })
    }
    if (key === "POST /api/v1/public/signing-session/cancel") {
      this.draft = false
      return route.fulfill({ status: 204 })
    }
    if (key === "POST /api/v1/public/signing-session/signature") {
      expect(request.postDataJSON()).toMatchObject({ version: 1 })
      this.draft = false
      this.submitted = true
      this.roster = this.roster.map((entry) => ({ ...entry, submitted: true }))
      for (const resolve of this.eventWaiters.splice(0)) resolve()
      return route.fulfill({ json: { submitted: true } })
    }
    if (key === `GET /api/v1/admin/boards/${boardId}/final.png`) {
      return route.fulfill({ body: png, contentType: "image/png" })
    }

    this.unknown.push(key)
    return route.fulfill({ json: { code: "TASK30_UNKNOWN_API" }, status: 599 })
  }
}

async function isolate(context: BrowserContext, api: MvpApi): Promise<void> {
  await context.addCookies([{ name: "XSRF-TOKEN", value: "synthetic-csrf", url: origin }])
  await context.route("**/api/v1/**", (route) => api.handle(route))
}

function observe(page: Page, allowedOrigin: string, pageErrors: string[], escapedOrigins: string[]): void {
  page.on("pageerror", (error) => pageErrors.push(error.message))
  page.on("request", (request) => {
    const url = new URL(request.url())
    if ((url.protocol === "http:" || url.protocol === "https:") && url.origin !== allowedOrigin) {
      escapedOrigins.push(request.url())
    }
  })
}

test("manager and signer complete one integrated synthetic event", async ({ browser }) => {
  test.skip(process.env.TASK30_MVP_MODE === "real", "mock contract is distinct from the authorized live-stack run")
  // Given: two isolated browser contexts share one stateful, fully-routed API fixture.
  const api = new MvpApi()
  const managerContext = await browser.newContext()
  const signerContext = await browser.newContext()
  await isolate(managerContext, api)
  await isolate(signerContext, api)
  const manager = await managerContext.newPage()
  const signer = await signerContext.newPage()
  const pageErrors: string[] = []
  const escapedOrigins: string[] = []
  observe(manager, origin, pageErrors, escapedOrigins)
  observe(signer, origin, pageErrors, escapedOrigins)

  // When: the manager signs in, creates the board, roster, slot, background, and opens sharing.
  await manager.goto("/login")
  await manager.getByLabel("이메일").fill("task30@example.invalid")
  await manager.getByLabel("비밀번호").fill("synthetic-password-phrase")
  await manager.getByRole("button", { name: "로그인" }).click()
  await manager.getByRole("link", { name: "새 보드 만들기" }).click()
  await manager.getByLabel("보드 제목").fill("통합 검증 행사")
  await manager.getByRole("button", { name: "보드 만들기" }).click()
  await manager.getByLabel("소속", { exact: true }).fill(identity.organization)
  await manager.getByLabel("직책", { exact: true }).fill(identity.job)
  await manager.getByLabel("이름", { exact: true }).fill(identity.name)
  await manager.getByRole("button", { name: "명단 추가" }).click()
  await manager.getByRole("button", { name: `${identity.name} 배치` }).click()
  await expect(manager.getByLabel("자동 저장 상태")).toHaveText("저장됨")
  await manager.getByLabel("PNG 또는 JPEG").setInputFiles({ buffer: png, mimeType: "image/png", name: "synthetic.png" })
  await manager.getByRole("button", { name: "기존 비율로 교체" }).click()
  await expect(manager.getByText("배경을 교체했습니다.")).toBeVisible()
  await manager.getByRole("button", { name: "서명 시작" }).click()
  await expect(manager.getByText("서명 진행", { exact: true })).toBeVisible()
  await expect(manager.getByText(`/sign/${shareToken}`, { exact: false })).toBeVisible()

  // When: full view listens to SSE while the second context identifies, draws, and submits.
  const fullView = await managerContext.newPage()
  observe(fullView, origin, pageErrors, escapedOrigins)
  await fullView.goto(`/boards/${boardId}/full`)
  await expect(fullView.getByTestId("submitted-signature")).toHaveCount(0)
  await signer.goto(`/sign/${shareToken}`)
  await signer.getByLabel("소속사 (선택)").fill(identity.organization)
  await signer.getByLabel("직책 (선택)").fill(identity.job)
  await signer.getByLabel("이름").fill(identity.name)
  await signer.getByRole("button", { name: "정보 확인" }).click()
  const canvas = signer.getByTestId("signer-canvas")
  const box = await canvas.boundingBox()
  if (box === null) throw new Error("Synthetic signer canvas is not visible")
  await signer.mouse.move(box.x + 40, box.y + 40)
  await signer.mouse.down()
  await signer.mouse.move(box.x + 140, box.y + 90, { steps: 4 })
  await signer.mouse.up()
  await signer.getByRole("button", { name: "서명 제출" }).click()

  // Then: SSE refreshes manager state before close, PNG download, and confirmed deletion.
  await expect(signer.getByTestId("public-signer-complete")).toBeVisible()
  await expect(fullView.getByTestId("submitted-signature")).toHaveCount(1)
  await manager.getByRole("button", { name: "마감" }).click()
  await expect(manager.getByText("마감/보관", { exact: true })).toBeVisible()
  const download = manager.waitForEvent("download")
  await manager.getByRole("button", { name: "최종 PNG 다운로드" }).click()
  expect((await download).suggestedFilename()).toBe("board-final.png")
  await manager.getByRole("button", { name: "보드 영구 삭제" }).click()
  await manager.getByRole("dialog", { name: "확인" }).getByRole("button", { name: "영구 삭제" }).click()
  await expect(manager).toHaveURL(/\/boards$/u)
  await expect(manager.getByText("아직 만든 보드가 없습니다.")).toBeVisible()
  expect(api.unknown).toEqual([])
  expect(pageErrors).toEqual([])
  expect(escapedOrigins).toEqual([])
  expect(api.calls).toEqual(expect.arrayContaining([
    "POST /api/v1/auth/login",
    "POST /api/v1/admin/boards",
    `POST /api/v1/admin/boards/${boardId}/roster`,
    `PATCH /api/v1/admin/boards/${boardId}/slots/${slotId}`,
    `POST /api/v1/admin/boards/${boardId}/background`,
    `POST /api/v1/admin/boards/${boardId}/open`,
    `GET /api/v1/admin/boards/${boardId}/events`,
    `POST /api/v1/public/links/${shareToken}/identify`,
    "PUT /api/v1/public/signing-session/draft",
    "POST /api/v1/public/signing-session/signature",
    `POST /api/v1/admin/boards/${boardId}/close`,
    `GET /api/v1/admin/boards/${boardId}/final.png`,
    `DELETE /api/v1/admin/boards/${boardId}`,
  ]))
  await signerContext.close()
  await managerContext.close()
})

test("authorized live stack completes one integrated synthetic event", async ({ browser }) => {
  test.skip(process.env.TASK30_MVP_MODE !== "real", "requires the separately authorized isolated Compose stack")
  const baseUrl = new URL(requiredEnvironment("TASK30_MVP_BASE_URL"))
  const { email, password } = readLiveCredentials()
  const managerContext = await browser.newContext({ baseURL: baseUrl.toString() })
  const signerContext = await browser.newContext({ baseURL: baseUrl.toString() })
  const manager = await managerContext.newPage()
  const signer = await signerContext.newPage()
  const pageErrors: string[] = []
  const escapedOrigins: string[] = []
  observe(manager, baseUrl.origin, pageErrors, escapedOrigins)
  observe(signer, baseUrl.origin, pageErrors, escapedOrigins)

  // Given: the authorized isolated stack has one runtime-created synthetic administrator.
  await manager.goto(new URL("/login", baseUrl).toString())
  await manager.getByLabel("이메일").fill(email)
  await manager.getByLabel("비밀번호").fill(password)
  await manager.getByRole("button", { name: "로그인" }).click()
  await expect(manager.getByRole("heading", { level: 1, name: "보드 목록" })).toBeVisible()

  // When: the manager creates, configures, and opens one real event.
  await manager.getByRole("link", { name: "새 보드 만들기" }).click()
  await manager.getByLabel("보드 제목").fill("통합 검증 행사")
  await manager.getByRole("button", { name: "보드 만들기" }).click()
  await manager.getByLabel("소속", { exact: true }).fill(identity.organization)
  await manager.getByLabel("직책", { exact: true }).fill(identity.job)
  await manager.getByLabel("이름", { exact: true }).fill(identity.name)
  await manager.getByRole("button", { name: "명단 추가" }).click()
  await manager.getByRole("button", { name: `${identity.name} 배치` }).click()
  await expect(manager.getByLabel("자동 저장 상태")).toHaveText("저장됨")
  await manager.getByLabel("PNG 또는 JPEG").setInputFiles({ buffer: png, mimeType: "image/png", name: "synthetic.png" })
  await manager.getByRole("button", { name: "기존 비율로 교체" }).click()
  await expect(manager.getByText("배경을 교체했습니다.")).toBeVisible()
  await manager.getByRole("button", { name: "서명 시작" }).click()
  await expect(manager.getByText("서명 진행", { exact: true })).toBeVisible()
  const shareUrl = await manager.getByRole("img", { name: "현재 서명 링크 QR" }).getAttribute("data-share-url")
  if (shareUrl === null) throw new Task30ConfigurationError("live share URL")
  const boardUrl = new URL(manager.url())
  const boardMatch = /^\/boards\/([0-9a-f-]+)\/edit$/u.exec(boardUrl.pathname)
  if (boardMatch === null) throw new Task30ConfigurationError("live board URL")
  const boardIdentifier = boardMatch[1]

  // When: the manager and public display listen through real SSE while a signer draws.
  const fullView = await managerContext.newPage()
  observe(fullView, baseUrl.origin, pageErrors, escapedOrigins)
  await fullView.goto(new URL(`/boards/${boardIdentifier}/full`, baseUrl).toString())
  await expect(fullView.getByTestId("submitted-signature")).toHaveCount(0)
  const displayUrl = new URL(shareUrl)
  displayUrl.pathname = displayUrl.pathname.replace(/^\/sign\//u, "/display/")
  const publicDisplay = await managerContext.newPage()
  observe(publicDisplay, baseUrl.origin, pageErrors, escapedOrigins)
  await publicDisplay.goto(displayUrl.toString())
  await expect(publicDisplay.getByTestId("submitted-signature")).toHaveCount(0)
  await signer.goto(shareUrl)
  await signer.getByLabel("소속사 (선택)").fill(identity.organization)
  await signer.getByLabel("직책 (선택)").fill(identity.job)
  await signer.getByLabel("이름").fill(identity.name)
  await signer.getByRole("button", { name: "정보 확인" }).click()
  const canvas = signer.getByTestId("signer-canvas")
  const box = await canvas.boundingBox()
  if (box === null) throw new Task30ConfigurationError("live signer canvas")
  await signer.mouse.move(box.x + 40, box.y + 40)
  await signer.mouse.down()
  await signer.mouse.move(box.x + 140, box.y + 90, { steps: 4 })
  await signer.mouse.up()
  await expect(fullView.getByTestId("submitted-signature")).toHaveCount(1)
  await expect(publicDisplay.getByTestId("submitted-signature")).toHaveCount(1)
  await signer.getByRole("button", { name: "서명 취소" }).click()
  await expect(signer.getByTestId("public-signer-identify")).toBeVisible()
  await expect(fullView.getByTestId("submitted-signature")).toHaveCount(0)
  await expect(publicDisplay.getByTestId("submitted-signature")).toHaveCount(0)

  const replacementContext = await browser.newContext({ baseURL: baseUrl.toString() })
  const replacement = await replacementContext.newPage()
  observe(replacement, baseUrl.origin, pageErrors, escapedOrigins)
  await replacement.goto(shareUrl)
  await replacement.getByLabel("소속사 (선택)").fill(identity.organization)
  await replacement.getByLabel("직책 (선택)").fill(identity.job)
  await replacement.getByLabel("이름").fill(identity.name)
  await replacement.getByRole("button", { name: "정보 확인" }).click()
  const replacementCanvas = replacement.getByTestId("signer-canvas")
  const replacementBox = await replacementCanvas.boundingBox()
  if (replacementBox === null) throw new Task30ConfigurationError("replacement signer canvas")
  await replacement.mouse.move(replacementBox.x + 40, replacementBox.y + 40)
  await replacement.mouse.down()
  await replacement.mouse.move(replacementBox.x + 140, replacementBox.y + 90, { steps: 4 })
  await replacement.mouse.up()
  await replacement.getByRole("button", { name: "서명 제출" }).click()

  // Then: a replacement device submits after cancellation and all views refresh before close.
  await expect(replacement.getByTestId("public-signer-complete")).toBeVisible()
  await expect(fullView.getByTestId("submitted-signature")).toHaveCount(1)
  await expect(publicDisplay.getByTestId("submitted-signature")).toHaveCount(1)
  await manager.getByRole("button", { name: "마감" }).click()
  await expect(manager.getByText("마감/보관", { exact: true })).toBeVisible()
  const download = manager.waitForEvent("download")
  await manager.getByRole("button", { name: "최종 PNG 다운로드" }).click()
  expect((await download).suggestedFilename()).toBe("board-final.png")
  await manager.getByRole("button", { name: "보드 영구 삭제" }).click()
  await manager.getByRole("dialog", { name: "확인" }).getByRole("button", { name: "영구 삭제" }).click()
  await expect(manager).toHaveURL(/\/boards$/u)
  await expect(manager.getByText("아직 만든 보드가 없습니다.")).toBeVisible()
  expect(pageErrors).toEqual([])
  expect(escapedOrigins).toEqual([])
  await replacementContext.close()
  await signerContext.close()
  await managerContext.close()
})
