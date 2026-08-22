import { expect, test, type Page, type Route } from "@playwright/test"
import { mkdir } from "node:fs/promises"
import path from "node:path"

const boardId = "11111111-1111-4111-8111-111111111111"
const evidenceDir = process.env.EDITOR_ERROR_TOAST_EVIDENCE_DIR
  ?? path.resolve("../.omo/evidence/task-32-safe-diagnostics")
const rosterPath = `/api/v1/admin/boards/${boardId}/roster`

function toast(page: Page) {
  return page.locator("output.app-toast")
}

function board(status: "contract mismatch" | "설정 중") {
  return {
    canvasHeight: 1080,
    canvasWidth: 1920,
    createdAt: "2026-08-21T00:00:00.000Z",
    id: boardId,
    shareLinkVersion: 1,
    status,
    title: "오류 알림 검증 보드",
    updatedAt: "2026-08-21T00:00:00.000Z",
  }
}

async function captureResponsiveToast(page: Page, state: string): Promise<void> {
  await mkdir(evidenceDir, { recursive: true })
  for (const [width, height] of [[1280, 800], [375, 812]] as const) {
    await page.setViewportSize({ width, height })
    await page.evaluate(() => window.scrollTo(0, 0))
    const popup = toast(page)
    await expect(popup).toBeVisible()
    const placement = await popup.evaluate((element) => {
      const box = element.getBoundingClientRect()
      const style = getComputedStyle(element)
      return {
        bottom: window.innerHeight - box.bottom,
        fitsViewport: element.scrollWidth <= element.clientWidth,
        pointerEvents: style.pointerEvents,
        position: style.position,
        right: window.innerWidth - box.right,
      }
    })
    expect(placement).toMatchObject({ fitsViewport: true, pointerEvents: "none", position: "fixed" })
    expect(placement.right).toBeGreaterThan(0)
    expect(placement.bottom).toBeGreaterThan(0)
    await page.screenshot({ fullPage: false, path: path.join(evidenceDir, `${state}-${width}x${height}.png`) })
  }
}

async function stubEditorApi(page: Page, status: "contract mismatch" | "설정 중"): Promise<void> {
  await page.route("**/api/v1/**", async (route: Route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    const headers = { "X-Request-ID": "qa-request-32" }
    if (pathname === "/api/v1/auth/session") {
      await route.fulfill({ headers, json: { authenticated: true, expiresAt: "2099-08-21T01:00:00.000Z" } })
      return
    }
    if (pathname === `/api/v1/admin/boards/${boardId}` && request.method() === "GET") {
      await route.fulfill({ headers, json: board(status) })
      return
    }
    if (pathname === rosterPath && request.method() === "POST") {
      await route.fulfill({
        headers,
        json: {
          code: "ROSTER_INVALID",
          errors: [{ code: "DUPLICATE_IDENTITY", row: 1 }],
          message: "private identity details",
          requestId: "qa-request-32",
        },
        status: 400,
      })
      return
    }
    if (pathname === rosterPath) {
      await route.fulfill({ headers, json: [] })
      return
    }
    if (pathname === `/api/v1/admin/boards/${boardId}/share`) {
      await route.fulfill({ headers, json: { shareToken: "safe-share", version: 1 } })
      return
    }
    if (pathname === `/api/v1/admin/boards/${boardId}/background`) {
      await route.fulfill({ headers, status: 204 })
      return
    }
    if (pathname.endsWith("/events")) {
      await route.fulfill({ status: 204 })
      return
    }
    await route.fulfill({ headers, json: { code: "NOT_FOUND" }, status: 404 })
  })
}

test("shows a lower-right diagnostic toast for an incompatible editor response", async ({ page }) => {
  // Given
  await stubEditorApi(page, "contract mismatch")

  // When
  await page.goto(`/boards/${boardId}/edit`)

  // Then
  await expect(page.getByRole("alert")).toHaveText("편집 화면을 불러오지 못했습니다.")
  await expect(toast(page)).toContainText("서버 응답 확인에 실패했습니다. 다시 시도해 주세요.")
  await expect(toast(page)).toContainText("요청 ID: qa-request-32")
  await expect(toast(page)).not.toContainText("contract mismatch")
  await captureResponsiveToast(page, "editor-contract-error")
  await expect(toast(page)).toBeHidden({ timeout: 6_000 })
})

test("shows a lower-right diagnostic toast while retaining a rejected roster draft", async ({ context, page }) => {
  // Given
  await context.addCookies([{ name: "XSRF-TOKEN", url: "http://127.0.0.1:4173", value: "test-csrf" }])
  await stubEditorApi(page, "설정 중")
  await page.goto(`/boards/${boardId}/edit`)
  const name = page.getByRole("textbox", { exact: true, name: "이름" }).first()

  // When
  await name.fill("나래")
  await page.getByRole("button", { name: "명단 추가" }).first().click()

  // Then
  await expect(toast(page)).toContainText("같은 명단이 중복되었습니다. 소속, 직책, 이름을 확인해 주세요.")
  await expect(toast(page)).toContainText("요청 ID: qa-request-32")
  await expect(name).toHaveValue("나래")
  await expect(page.locator("body")).not.toContainText("private identity details")
  await captureResponsiveToast(page, "roster-add-rejected")
  await expect(toast(page)).toBeHidden({ timeout: 6_000 })
  await expect(name).toHaveValue("나래")
})
