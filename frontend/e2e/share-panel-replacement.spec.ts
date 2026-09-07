import { expect, test, type Page, type Request } from "@playwright/test"
import { readFileSync } from "node:fs"
import { mkdir, writeFile } from "node:fs/promises"
import path from "node:path"
import { z } from "zod"

const evidenceDir = path.resolve(process.env.TASK7_REAL_EVIDENCE_DIR ?? "../.omo/evidence/public-display-live-ink")
const identity = { job: "진행", name: "하늘", organization: "나래 테스트" }
const png = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
)
const credentialsSchema = z.strictObject({
  email: z.email(),
  password: z.string().min(15),
})

function requiredEnvironment(name: string): string {
  const value = process.env[name]
  if (value === undefined || value.length === 0) throw new Error(`Task 7 real stack requires ${name}`)
  return value
}

function readLiveCredentials(): z.infer<typeof credentialsSchema> {
  const fifo = requiredEnvironment("TASK30_CREDENTIAL_FIFO")
  return credentialsSchema.parse(JSON.parse(readFileSync(fifo, "utf8")))
}

function isForceReplaceRequest(request: Request, boardId: string): boolean {
  return request.method() === "POST"
    && new URL(request.url()).pathname === `/api/v1/admin/boards/${boardId}/display/force-replace`
}

async function createOpenBoard(page: Page, baseUrl: URL, email: string, password: string): Promise<{ boardId: string; shareUrl: string }> {
  await page.goto(new URL("/login", baseUrl).toString())
  await page.getByLabel("이메일").fill(email)
  await page.getByLabel("비밀번호").fill(password)
  await page.getByRole("button", { name: "로그인" }).click()
  await expect(page.getByRole("heading", { level: 1, name: "보드 목록" })).toBeVisible()

  await page.getByRole("link", { name: "새 보드 만들기" }).click()
  await page.getByLabel("보드 제목").fill("관리자 화면 교체 검증")
  await page.getByRole("button", { name: "보드 만들기" }).click()
  await page.getByLabel("소속", { exact: true }).fill(identity.organization)
  await page.getByLabel("직책", { exact: true }).fill(identity.job)
  await page.getByLabel("이름", { exact: true }).fill(identity.name)
  await page.getByRole("button", { name: "명단 추가" }).click()
  await page.getByRole("button", { name: `${identity.name} 배치` }).click()
  await expect(page.getByLabel("자동 저장 상태")).toHaveText("저장됨")
  await page.getByLabel("PNG 또는 JPEG").setInputFiles({ buffer: png, mimeType: "image/png", name: "task-7.png" })
  await page.getByRole("button", { name: "기존 비율로 교체" }).click()
  await expect(page.getByText("배경을 교체했습니다.")).toBeVisible()
  await page.getByRole("button", { name: "서명 시작" }).click()
  await expect(page.getByText("서명 진행", { exact: true })).toBeVisible()

  const shareUrl = await page.getByRole("img", { name: "현재 서명 링크 QR" }).getAttribute("data-share-url")
  if (shareUrl === null) throw new Error("Task 7 real stack did not expose the share URL")
  const match = /^\/boards\/([0-9a-f-]+)\/edit$/u.exec(new URL(page.url()).pathname)
  const boardId = match?.[1]
  if (boardId === undefined) throw new Error("Task 7 real stack did not navigate to an editor board URL")
  return { boardId, shareUrl }
}

test.describe("@real administrator display replacement", () => {
  test("uses two real contexts, one authorized request, and real display-replaced SSE", async ({ browser }) => {
    test.skip(process.env.TASK30_MVP_MODE !== "real", "requires the isolated Compose stack and runtime credentials")
    test.setTimeout(90_000)
    const baseUrl = new URL(requiredEnvironment("TASK30_MVP_BASE_URL"))
    const { email, password } = readLiveCredentials()
    await mkdir(evidenceDir, { recursive: true })

    const adminContext = await browser.newContext({
      baseURL: baseUrl.toString(),
      extraHTTPHeaders: { "X-Narae-Client-IP": "127.0.0.1" },
      ignoreHTTPSErrors: true,
    })
    const publicContext = await browser.newContext({ baseURL: baseUrl.toString(), ignoreHTTPSErrors: true })
    let adminTraceStarted = false
    let publicTraceStarted = false
    try {
      await adminContext.tracing.start({ screenshots: true, snapshots: true, sources: true })
      adminTraceStarted = true
      await publicContext.tracing.start({ screenshots: true, snapshots: true, sources: true })
      publicTraceStarted = true

      const admin = await adminContext.newPage()
      const oldDisplay = await publicContext.newPage()
      const { boardId, shareUrl } = await createOpenBoard(admin, baseUrl, email, password)
      const sharePanel = admin.getByRole("region", { name: "공유" })
      const shareImage = sharePanel.getByRole("img", { name: "현재 서명 링크 QR" })
      await expect(shareImage).toHaveAttribute("data-share-url", shareUrl)
      const adminCookies = await adminContext.cookies(new URL("/api/v1/auth/session", baseUrl).toString())
      expect(adminCookies.some((cookie) => cookie.name === "ADMIN_SESSION")).toBe(true)

      const displayUrl = new URL(shareUrl)
      displayUrl.protocol = baseUrl.protocol
      displayUrl.host = baseUrl.host
      displayUrl.pathname = displayUrl.pathname.replace(/^\/sign\//u, "/display/")
      const displayClaim = oldDisplay.waitForResponse((response) => response.request().method() === "POST"
        && new URL(response.url()).pathname.endsWith(`/public/links/${displayUrl.pathname.split("/").at(-1)}/display/claim`))
      const displayEvents = oldDisplay.waitForResponse((response) => response.request().method() === "GET"
        && new URL(response.url()).pathname.endsWith(`/public/links/${displayUrl.pathname.split("/").at(-1)}/display/events`))
      await oldDisplay.goto(displayUrl.toString(), { waitUntil: "domcontentloaded" })
      expect((await displayClaim).status()).toBe(204)
      await expect(oldDisplay.getByTestId("full-view-canvas")).toBeVisible()
      expect((await displayEvents).status()).toBe(200)

      let forceRequests = 0
      let csrfHeaderPresent = false
      admin.on("request", (request) => {
        if (!isForceReplaceRequest(request, boardId)) return
        forceRequests += 1
        csrfHeaderPresent = request.headers()["x-xsrf-token"] !== undefined
      })

      await sharePanel.getByRole("button", { name: "행사장 화면 교체" }).click()
      await expect(admin.getByText(/현재 행사장 화면의 표시 연결을 종료/u)).toBeVisible()
      await admin.screenshot({ path: path.join(evidenceDir, "task-7-real-confirmation.png"), fullPage: true })
      await admin.getByRole("button", { name: "취소" }).click()
      await expect.poll(() => forceRequests).toBe(0)
      await expect(shareImage).toHaveAttribute("data-share-url", shareUrl)

      await sharePanel.getByRole("button", { name: "행사장 화면 교체" }).click()
      const forceRequest = admin.waitForRequest((request) => isForceReplaceRequest(request, boardId))
      const forceResponse = admin.waitForResponse((response) => isForceReplaceRequest(response.request(), boardId))
      await admin.getByRole("button", { exact: true, name: "화면 교체" }).click()
      await forceRequest
      expect((await forceResponse).status()).toBe(204)
      await expect.poll(() => forceRequests).toBe(1)
      await admin.waitForTimeout(300)
      expect(forceRequests).toBe(1)
      expect(csrfHeaderPresent).toBe(true)
      await expect(oldDisplay.getByRole("alert")).toHaveText("이 화면의 표시 연결이 다른 화면으로 전환되었습니다.")
      await oldDisplay.screenshot({ path: path.join(evidenceDir, "task-7-real-display-replaced.png"), fullPage: true })
      await expect(shareImage).toHaveAttribute("data-share-url", shareUrl)

      await writeFile(path.join(evidenceDir, "task-7-real-network-summary.json"), `${JSON.stringify({
        adminSession: true,
        cancelForceRequests: 0,
        confirmForceRequests: forceRequests,
        csrfHeaderPresent,
        forceResponseStatus: 204,
        oldDisplayNotice: "display-replaced",
        shareUrlUnchanged: true,
      }, null, 2)}\n`)
    } finally {
      if (publicTraceStarted) await publicContext.tracing.stop({ path: path.join(evidenceDir, "task-7-real-public-display-trace.zip") })
      if (adminTraceStarted) await adminContext.tracing.stop({ path: path.join(evidenceDir, "task-7-real-admin-trace.zip") })
      await publicContext.close()
      await adminContext.close()
      await writeFile(path.join(evidenceDir, "task-7-real-cleanup.json"), `${JSON.stringify({ adminContextClosed: true, publicContextClosed: true })}\n`)
    }
  })
})
