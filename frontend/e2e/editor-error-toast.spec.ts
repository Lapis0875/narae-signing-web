import { expect, test, type Page, type Route } from "@playwright/test"
import { mkdir } from "node:fs/promises"
import path from "node:path"

const boardId = "11111111-1111-4111-8111-111111111111"
const evidenceDir = process.env.EDITOR_ERROR_TOAST_EVIDENCE_DIR ?? path.resolve("../.omo/evidence/editor-error-toast")
const now = "2026-08-21T00:00:00.000Z"
const rosterPath = `/api/v1/admin/boards/${boardId}/roster`

function toast(page: Page) {
  return page.locator("output.app-toast")
}

function board(status: "생성 중" | "설정 중") {
  return {
    canvasHeight: 1080,
    canvasWidth: 1920,
    createdAt: now,
    id: boardId,
    shareLinkVersion: 1,
    status,
    title: "오류 알림 검증 보드",
    updatedAt: now,
  }
}

async function captureToast(page: Page, state: string) {
  await mkdir(evidenceDir, { recursive: true })
  for (const [width, height] of [[1280, 800], [375, 812]] as const) {
    await page.setViewportSize({ width, height })
    const toastElement = toast(page)
    await expect(toastElement).toBeVisible()
    const placement = await toastElement.evaluate((element) => {
      const box = element.getBoundingClientRect()
      return {
        bottom: window.innerHeight - box.bottom,
        position: getComputedStyle(element).position,
        pointerEvents: getComputedStyle(element).pointerEvents,
        right: window.innerWidth - box.right,
      }
    })
    expect(placement.position).toBe("fixed")
    expect(placement.pointerEvents).toBe("none")
    expect(placement.right).toBeGreaterThan(0)
    expect(placement.bottom).toBeGreaterThan(0)
    await page.screenshot({ fullPage: false, path: path.join(evidenceDir, `${state}-${width}.png`) })
  }
}

async function stubEditorApi(page: Page, status: "생성 중" | "설정 중") {
  await page.route("**/api/v1/**", async (route: Route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === "/api/v1/auth/session") {
      return route.fulfill({ json: { authenticated: true, expiresAt: "2026-08-21T01:00:00.000Z" } })
    }
    if (pathname === `/api/v1/admin/boards/${boardId}` && request.method() === "GET") return route.fulfill({ json: board(status) })
    if (pathname === rosterPath && request.method() === "POST") {
      return route.fulfill({ json: { code: "ROSTER_INVALID", errors: [{ code: "DUPLICATE_IDENTITY", row: 1 }] }, status: 400 })
    }
    if (pathname === rosterPath) return route.fulfill({ json: [] })
    if (pathname === `/api/v1/admin/boards/${boardId}/share`) return route.fulfill({ json: { shareToken: "safe-share", version: 1 } })
    if (pathname === `/api/v1/admin/boards/${boardId}/background`) return route.fulfill({ status: 204 })
    if (pathname.endsWith("/events")) return route.fulfill({ status: 204 })
    return route.fulfill({ json: { code: "NOT_FOUND" }, status: 404 })
  })
}

test("shows a lower-right diagnostic toast for an incompatible editor response", async ({ context, page }) => {
  // Given
  await context.addCookies([{ name: "XSRF-TOKEN", url: "http://127.0.0.1:4173", value: "test-csrf" }])
  await stubEditorApi(page, "생성 중")

  // When
  await page.goto(`/boards/${boardId}/edit`)

  // Then
  await expect(page.getByRole("alert")).toHaveText("편집 화면을 불러오지 못했습니다.")
  await expect(toast(page)).toContainText("응답 형식 오류입니다.")
  await expect(toast(page)).toContainText("status")
  await captureToast(page, "editor-contract-error")
})

test("shows a lower-right diagnostic toast when roster addition is rejected", async ({ context, page }) => {
  // Given
  await context.addCookies([{ name: "XSRF-TOKEN", url: "http://127.0.0.1:4173", value: "test-csrf" }])
  await stubEditorApi(page, "설정 중")
  await page.goto(`/boards/${boardId}/edit`)
  await expect(page.getByRole("button", { name: "명단 추가" })).toBeVisible()

  // When
  await page.getByRole("textbox", { exact: true, name: "이름" }).fill("나래")
  await page.getByRole("button", { name: "명단 추가" }).click()

  // Then
  await expect(toast(page)).toContainText("명단을 추가하지 못했습니다.")
  await expect(toast(page)).toContainText("같은 명단이 중복되었습니다.")
  await captureToast(page, "roster-add-rejected")
  await expect(toast(page)).toBeHidden({ timeout: 6_000 })
})
