import { expect, test } from "@playwright/test"

const futureIso = (minutes: number) => new Date(Date.now() + minutes * 60_000).toISOString()

test.describe("@admin-auth", () => {
  test("logs in and redirects an authenticated administrator", async ({ context, page }, testInfo) => {
    // Given
    let authenticated = false
    await context.addCookies([{ name: "XSRF-TOKEN", value: "csrf-test", url: "http://127.0.0.1:4173" }])
    await page.route("**/api/v1/auth/csrf", (route) => route.fulfill({ status: 204 }))
    await page.route("**/api/v1/auth/session", (route) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify(authenticated
          ? { authenticated: true, expiresAt: futureIso(60) }
          : { authenticated: false, expiresAt: null }),
      }),
    )
    await page.route("**/api/v1/auth/login", async (route) => {
      expect(route.request().headers()["x-xsrf-token"]).toBe("csrf-test")
      authenticated = true
      await route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ authenticated: true, expiresAt: futureIso(60) }),
      })
    })
    await page.route("**/api/v1/admin/boards", (route) =>
      route.fulfill({ contentType: "application/json", body: JSON.stringify({ status: "ready" }) }),
    )

    // When
    await page.goto("/login")
    await page.getByLabel("이메일").fill("operator@example.com")
    await page.getByLabel("비밀번호").fill("correct-password-phrase")
    await page.getByRole("button", { name: "로그인" }).click()

    // Then
    await expect(page).toHaveURL(/\/boards$/)
    await expect(page.getByRole("heading", { level: 1, name: "보드 목록" })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath("login-success-desktop.png"), fullPage: true })
  })

  test("shows the expiry notice at the 31 and 29 minute boundary", async ({ page }, testInfo) => {
    // Given
    let remainingMinutes = 31
    await page.setViewportSize({ height: 1024, width: 768 })
    await page.route("**/api/v1/auth/session", (route) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ authenticated: true, expiresAt: futureIso(remainingMinutes) }),
      }),
    )
    await page.route("**/api/v1/admin/boards", (route) =>
      route.fulfill({ contentType: "application/json", body: JSON.stringify({ status: "ready" }) }),
    )

    // When
    await page.goto("/boards")

    // Then
    await expect(page.getByTestId("session-expiry-notice")).toHaveCount(0)

    // Given
    remainingMinutes = 29

    // When
    await page.reload()

    // Then
    await expect(page.getByTestId("session-expiry-notice")).toBeVisible()
    await expect(page.getByTestId("session-expiry-notice")).toContainText("29분")
    await page.screenshot({ path: testInfo.outputPath("expiry-warning-tablet.png"), fullPage: true })
  })

  test("logout clears private content and returns to login", async ({ context, page }, testInfo) => {
    // Given
    let authenticated = true
    await context.addCookies([{ name: "XSRF-TOKEN", value: "csrf-test", url: "http://127.0.0.1:4173" }])
    await page.route("**/api/v1/auth/csrf", (route) => route.fulfill({ status: 204 }))
    await page.route("**/api/v1/auth/session", (route) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify(authenticated
          ? { authenticated: true, expiresAt: futureIso(60) }
          : { authenticated: false, expiresAt: null }),
      }),
    )
    await page.route("**/api/v1/auth/logout", async (route) => {
      authenticated = false
      await route.fulfill({ status: 204 })
    })
    await page.route("**/api/v1/admin/boards", (route) =>
      route.fulfill({ contentType: "application/json", body: JSON.stringify({ status: "ready" }) }),
    )
    await page.goto("/boards")
    await expect(page.getByRole("heading", { level: 1, name: "보드 목록" })).toBeVisible()

    // When
    await page.getByRole("button", { name: "로그아웃" }).click()

    // Then
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByRole("heading", { level: 1, name: "관리자 로그인" })).toBeVisible()
    await expect(page.getByText("세션이 만료되었습니다. 다시 로그인해 주세요.")).toHaveCount(0)
    await expect(page.getByRole("heading", { level: 1, name: "보드 목록" })).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath("logout-login-desktop.png"), fullPage: true })
  })

  test("redirects after an expired save without keeping private query content", async ({ page }, testInfo) => {
    // Given
    let authenticated = true
    await page.route("**/api/v1/auth/session", (route) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify(authenticated
          ? { authenticated: true, expiresAt: futureIso(60) }
          : { authenticated: false, expiresAt: null }),
      }),
    )
    await page.route("**/api/v1/admin/boards", async (route) => {
      if (route.request().method() === "POST") {
        authenticated = false
        await route.fulfill({
          contentType: "application/json",
          status: 401,
          body: JSON.stringify({ code: "UNAUTHORIZED" }),
        })
        return
      }
      await route.fulfill({ contentType: "application/json", body: JSON.stringify({ status: "ready" }) })
    })
    await page.goto("/boards")
    await expect(page.getByRole("heading", { level: 1, name: "보드 목록" })).toBeVisible()

    // When
    await page.evaluate(async () => {
      const queryClientModule = "/src/app/queryClient.ts"
      const apiClientModule = "/src/api/client.ts"
      const { queryClient } = await import(queryClientModule)
      const { apiRequest, setCsrfToken } = await import(apiClientModule)
      setCsrfToken("csrf-test")
      try {
        await queryClient.getMutationCache().build(queryClient, {
          mutationFn: () => apiRequest("/api/v1/admin/boards", { method: "POST" }),
        }).execute()
      } catch (error) {
        if (!(error instanceof Error)) {
          throw error
        }
      }
    })

    // Then
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByRole("heading", { level: 1, name: "관리자 로그인" })).toBeVisible()
    await expect(page.getByRole("alert")).toContainText("세션이 만료되었습니다")
    await expect(page.getByRole("heading", { level: 1, name: "보드 목록" })).toHaveCount(0)
    await page.waitForFunction(() =>
      document.querySelector("#admin-email") !== null
      && document.querySelector("#admin-password") !== null
      && document.querySelector("[data-testid='loading-view']") === null,
    )
    await page.evaluate(async () => {
      await document.fonts.ready
      await new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
    })
    await page.screenshot({ path: testInfo.outputPath("expired-request-login-desktop.png"), fullPage: true })
  })

  test("keeps failure and lockout messaging generic without rendering credentials or cookies", async ({
    context,
    page,
  }, testInfo) => {
    // Given
    let loginStatus = 401
    await page.setViewportSize({ height: 1024, width: 768 })
    await context.addCookies([{ name: "XSRF-TOKEN", value: "private-cookie-value", url: "http://127.0.0.1:4173" }])
    await page.route("**/api/v1/auth/csrf", (route) => route.fulfill({ status: 204 }))
    await page.route("**/api/v1/auth/session", (route) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ authenticated: false, expiresAt: null }),
      }),
    )
    await page.route("**/api/v1/auth/login", (route) =>
      route.fulfill({
        contentType: "application/json",
        status: loginStatus,
        body: JSON.stringify({ code: loginStatus === 429 ? "LOGIN_RATE_LIMITED" : "AUTHENTICATION_FAILED" }),
      }),
    )
    await page.goto("/login")
    await page.getByLabel("이메일").fill("operator@example.com")
    await page.getByLabel("비밀번호").fill("wrong-password-phrase")

    // When
    await page.getByRole("button", { name: "로그인" }).click()

    // Then
    await expect(page.getByRole("alert")).toContainText("로그인에 실패했습니다")
    await expect(page.getByLabel("이메일")).toHaveValue("")
    await expect(page.getByLabel("비밀번호")).toHaveValue("")

    // Given
    loginStatus = 429
    await page.getByLabel("이메일").fill("operator@example.com")
    await page.getByLabel("비밀번호").fill("wrong-password-phrase")

    // When
    await page.getByRole("button", { name: "로그인" }).click()

    // Then
    await expect(page.getByRole("alert")).toContainText("15분 후")
    await expect(page.getByLabel("이메일")).toHaveValue("")
    await expect(page.getByLabel("비밀번호")).toHaveValue("")
    await expect(page.locator("body")).not.toContainText("operator@example.com")
    await expect(page.locator("body")).not.toContainText("wrong-password-phrase")
    await expect(page.locator("body")).not.toContainText("private-cookie-value")
    await page.screenshot({ path: testInfo.outputPath("generic-lockout-tablet.png"), fullPage: true })
  })
})
