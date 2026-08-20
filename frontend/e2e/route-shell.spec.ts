import { expect, test } from "@playwright/test"

const routes = [
  { path: "/login", title: "관리자 로그인" },
  { path: "/boards", title: "보드 목록" },
  { path: "/boards/new", title: "새 보드" },
  { path: "/boards/board-1/edit", title: "보드 편집" },
  { path: "/boards/board-1/full", title: "보드 전체보기" },
  { path: "/sign/share-1", title: "서명하기" },
] as const

test.describe("@route-shell", () => {
  for (const route of routes) {
    test(`renders ${route.path} from a mock API response`, async ({ page }, testInfo) => {
      // Given
      await page.route("**/api/v1/**", (request) =>
        request.fulfill({ contentType: "application/json", body: JSON.stringify({ status: "ready" }) }),
      )

      // When
      await page.goto(route.path)

      // Then
      await expect(page.getByRole("heading", { level: 1, name: route.title })).toBeVisible()
      await page.screenshot({ path: testInfo.outputPath(`${route.path.replaceAll("/", "-") || "root"}.png`) })
    })
  }

  test("keeps the signer canvas unmounted on a phone viewport", async ({ page }, testInfo) => {
    // Given
    await page.setViewportSize({ height: 844, width: 390 })

    // When
    await page.goto("/sign/share-1")

    // Then
    await expect(page.getByTestId("unsupported-device-view")).toBeVisible()
    await expect(page.getByTestId("signer-canvas")).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath("phone-unsupported.png") })
  })
})
