import { expect, test } from "@playwright/test"
import type { Page, Route } from "@playwright/test"
const shareToken = "public-test-token"
const identity = { organization: "나래 미디어", job: "", name: "홍 길동" } as const
async function addCsrf(page: Page) {
  await page.context().addCookies([{ name: "XSRF-TOKEN", url: "http://127.0.0.1:4173", value: "csrf-test" }])
}
async function drawOneStroke(page: Page) {
  const canvas = page.getByTestId("signer-canvas")
  const box = await canvas.boundingBox()
  if (box === null) {
    throw new Error("Signer canvas has no bounding box")
  }
  await page.mouse.move(box.x + 40, box.y + 40)
  await page.mouse.down()
  await page.mouse.move(box.x + 120, box.y + 90, { steps: 4 })
  await page.mouse.up()
  await expect(canvas).toHaveAttribute("data-stroke-count", "1")
}
async function focusSubmit(page: Page) {
  const button = page.getByRole("button", { name: "서명 제출" })
  await page.getByRole("link", { name: "나래 서명 보드" }).focus()
  for (let index = 0; index < 2; index += 1) await page.keyboard.press("Tab")
  await expect(button).toBeFocused()
  await expect(button).toHaveCSS("outline-style", "solid")
  return button
}
async function expectHeadingBounded(page: Page) {
  const heading = page.getByRole("heading")
  const [box, viewport] = [await heading.boundingBox(), page.viewportSize()]
  expect(await heading.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  expect((box?.x ?? 0) + (box?.width ?? Number.POSITIVE_INFINITY)).toBeLessThanOrEqual(viewport?.width ?? 0)
}
test.describe("@public-signer device policy", () => {
  for (const viewport of [
    { height: 812, width: 375 }, { height: 844, width: 390 },
    { height: 768, width: 599 }, { height: 599, width: 768 },
  ]) {
    test(`blocks ${viewport.width}x${viewport.height} without a public request`, async ({ page }, testInfo) => {
      const publicRequests: string[] = []
      await page.setViewportSize(viewport)
      page.on("request", (request) => {
        if (request.url().includes("/api/v1/public/")) publicRequests.push(request.url())
      })
      await page.goto(`/sign/${shareToken}`)
      await expect(page.getByTestId("unsupported-device-view")).toBeVisible()
      await expectHeadingBounded(page)
      await expect(page.getByTestId("signer-canvas")).toHaveCount(0)
      expect(publicRequests).toEqual([])
      await page.screenshot({ path: testInfo.outputPath(`unsupported-${viewport.width}.png`), fullPage: true })
    })
  }
  for (const viewport of [{ height: 768, width: 600 }, { height: 600, width: 768 }]) {
    test(`accepts the exact ${viewport.width}x${viewport.height} boundary`, async ({ page }) => {
      const requests: string[] = []
      await addCsrf(page)
      await page.setViewportSize(viewport)
      await page.route("**/api/v1/public/**", async (route) => {
        requests.push(new URL(route.request().url()).pathname)
        if (route.request().url().endsWith("/signing-session")) {
          await route.fulfill({ contentType: "application/json", status: 401, body: "{}" })
          return
        }
        await route.fulfill({ contentType: "application/json", body: JSON.stringify({ state: "OPEN", title: "경계 행사" }) })
      })
      await page.goto(`/sign/${shareToken}`)
      await expect(page.getByTestId("public-signer-identify")).toBeVisible()
      expect(requests).toHaveLength(2)
    })
  }
})
test.describe("@public-signer link states", () => {
  for (const failure of [
    { expected: "forbidden-view", name: "forbidden", status: 403, width: 600 },
    { expected: "error-view", name: "generic error boundary", status: 500, width: 600 },
    { expected: "error-view", name: "generic error", status: 500, width: 768 },
    { expected: "error-view", name: "generic error wide", status: 500, width: 1280 },
  ]) {
    test(`shows the safe ${failure.name} view without backend prose`, async ({ page }) => {
      await page.setViewportSize({ height: 1024, width: failure.width })
      await page.route("**/api/v1/public/links/**", (route) => route.fulfill({ contentType: "application/json",
        status: failure.status, body: JSON.stringify({ message: "other participant secret" }) }))
      await page.goto(`/sign/${shareToken}`)
      await expect(page.getByTestId(failure.expected)).toBeVisible()
      await expectHeadingBounded(page)
      const mainBox = await page.locator(".app-main").boundingBox()
      expect((mainBox?.x ?? -1) >= 12 && (mainBox?.x ?? 0) + (mainBox?.width ?? Number.POSITIVE_INFINITY) <= failure.width - 12).toBe(true)
      await expect(page.getByText("other participant secret")).toHaveCount(0)
      await expect(page.getByTestId("signer-canvas")).toHaveCount(0)
    })
  }
  for (const fixture of [
    { expected: "public-signer-setup", name: "setup", response: { state: "SETUP", title: "준비 행사" } },
    { expected: "public-signer-closed", name: "closed", response: { state: "CLOSED", title: "마감 행사" } },
    { expected: "public-signer-invalid", name: "invalid", response: { state: "INVALID", title: null } },
  ]) {
    test(`shows the safe ${fixture.name} state`, async ({ page }) => {
      await page.setViewportSize({ height: 1024, width: 768 })
      await page.route("**/api/v1/public/links/**", (route) => route.fulfill({ contentType: "application/json",
        body: JSON.stringify(fixture.response) }))
      await page.goto(`/sign/${shareToken}`)
      await expect(page.getByTestId(fixture.expected)).toBeVisible()
      await expect(page.getByTestId("signer-canvas")).toHaveCount(0)
    })
  }
  test("fails an old or malformed link closed without backend prose", async ({ page }) => {
    await page.setViewportSize({ height: 1024, width: 768 })
    await page.route("**/api/v1/public/links/**", (route) => route.fulfill({ contentType: "application/json", status: 404,
      body: JSON.stringify({ code: "internal_old_link", message: "other participant secret" }) }))
    await page.goto(`/sign/${shareToken}`)
    await expect(page.getByTestId("public-signer-invalid")).toBeVisible()
    await expect(page.getByText("other participant secret")).toHaveCount(0)
  })
})
test.describe("@public-signer contract fixture completion", () => {
  for (const viewport of [{ height: 768, width: 600 }, { height: 1024, width: 768 }, { height: 768, width: 1024 }]) {
    test(`identifies, retries, and re-enters at ${viewport.width}x${viewport.height}`, async ({ page }, testInfo) => {
      let identified = false
      let linkRequests = 0
      let submitted = false
      let signatureRequests = 0
      let releaseIdentify: (() => void) | undefined
      let releaseLink: (() => void) | undefined
      let releaseSuccess: (() => void) | undefined
      await addCsrf(page)
      await page.setViewportSize(viewport)
      await page.route("**/api/v1/public/**", async (route: Route) => {
        const request = route.request()
        const path = new URL(request.url()).pathname
        if (path.endsWith("/identify")) {
          expect(request.postDataJSON()).toEqual(identity)
          await new Promise<void>((resolve) => { releaseIdentify = resolve })
          identified = true
          await route.fulfill({ contentType: "application/json", body: JSON.stringify({ identified: true }) })
          return
        }
        if (path.endsWith("/signing-session/signature")) {
          signatureRequests += 1
          if (signatureRequests === 1) {
            await route.abort("failed")
            return
          }
          await new Promise<void>((resolve) => { releaseSuccess = resolve })
          submitted = true
          await route.fulfill({ contentType: "application/json", body: JSON.stringify({ submitted: true }) })
          return
        }
        if (path.endsWith("/signing-session")) {
          if (!identified) {
            await route.fulfill({ contentType: "application/json", status: 401, body: "{}" })
            return
          }
          await route.fulfill({ contentType: "application/json", body: JSON.stringify({
            signatureAspectRatio: submitted ? undefined : 1.777778,
            state: submitted ? "SUBMITTED" : "READY",
          }) })
          return
        }
        linkRequests += 1
        if (linkRequests === 1) await new Promise<void>((resolve) => { releaseLink = resolve })
        await route.fulfill({ contentType: "application/json", body: JSON.stringify({ state: "OPEN", title: "나래 행사" }) })
      })
      await page.goto(`/sign/${shareToken}`)
      await expect(page.getByTestId("loading-view")).toBeVisible()
      releaseLink?.()
      await expect(page.getByTestId("public-signer-identify")).toBeVisible()
      await page.screenshot({ path: testInfo.outputPath(`identify-${viewport.width}.png`), fullPage: true })
      await page.getByLabel("소속사 (선택)").fill(identity.organization)
      await page.getByLabel("직책 (선택)").fill(identity.job)
      await page.getByLabel("이름").fill(identity.name)
      await page.getByRole("button", { name: "정보 확인" }).click()
      await expect(page.getByTestId("public-signer-identified")).toBeVisible()
      await page.screenshot({ path: testInfo.outputPath(`identified-${viewport.width}.png`), fullPage: true })
      releaseIdentify?.()
      await expect(page.getByTestId("public-signer-drawing")).toBeVisible()
      await expect(page.getByTestId("retention-notice")).toBeVisible()
      const readyBox = await page.getByTestId("signer-canvas").boundingBox()
      expect(readyBox).not.toBeNull()
      expect((readyBox?.width ?? 0) / (readyBox?.height ?? 1)).toBeCloseTo(1.777778, 2)
      await page.screenshot({ path: testInfo.outputPath(`ready-aspect-${viewport.width}.png`) })
      await drawOneStroke(page)
      await page.screenshot({ path: testInfo.outputPath(`drawing-stroke-${viewport.width}.png`) })
      const submitButton = page.getByRole("button", { name: "서명 제출" })
      if (testInfo.project.name === "chromium") {
        await focusSubmit(page)
        await page.screenshot({ path: testInfo.outputPath(`focus-visible-${viewport.width}.png`) })
      }
      await submitButton.click()
      await expect(page.getByTestId("signature-status")).toContainText("다시 시도")
      await expect(page.getByTestId("signer-canvas")).toHaveAttribute("data-stroke-count", "1")
      expect(signatureRequests).toBe(1)
      await page.screenshot({ path: testInfo.outputPath(`failure-retained-${viewport.width}.png`) })
      await expect(submitButton).toBeEnabled()
      if (testInfo.project.name === "chromium") {
        await focusSubmit(page)
        await page.screenshot({ path: testInfo.outputPath(`explicit-retry-${viewport.width}.png`) })
      }
      await submitButton.click()
      await expect(page.getByRole("button", { name: "저장 중" })).toBeDisabled()
      await page.screenshot({ path: testInfo.outputPath(`submitting-disabled-${viewport.width}.png`) })
      releaseSuccess?.()
      await expect(page.getByTestId("public-signer-complete")).toBeVisible()
      await page.reload()
      await expect(page.getByTestId("public-signer-complete")).toBeVisible()
      await expect(page.getByText(identity.name)).toHaveCount(0)
      await expect(page.getByText(identity.organization)).toHaveCount(0)
      const storage = await page.evaluate(async () => ({
        cacheKeys: "caches" in window ? await caches.keys() : [],
        databases: "databases" in indexedDB ? await indexedDB.databases() : [],
        local: Object.entries(localStorage),
        session: Object.entries(sessionStorage),
      }))
      expect(JSON.stringify(storage)).not.toContain(shareToken)
      expect(JSON.stringify(storage)).not.toContain(identity.name)
    })
  }
})
test.describe("@public-signer expired and stale sessions", () => {
  for (const expiry of ["absolute", "idle"]) {
    test(`returns an ${expiry}-expired session to safe identification`, async ({ page }) => {
      await addCsrf(page)
      await page.setViewportSize({ height: 1024, width: 768 })
      await page.route("**/api/v1/public/**", (route) => route.fulfill({
        contentType: "application/json",
        status: route.request().url().endsWith("/signing-session") ? 401 : 200,
        body: route.request().url().endsWith("/signing-session")
          ? "{}"
          : JSON.stringify({ state: "OPEN", title: "만료 행사" }),
      }))
      await page.goto(`/sign/${shareToken}`)
      await expect(page.getByTestId("public-signer-identify")).toBeVisible()
      await expect(page.getByTestId("signer-canvas")).toHaveCount(0)
      await expect(page.getByText(identity.name)).toHaveCount(0)
    })
  }
  test("returns a stale signer session to safe identification", async ({ page }, testInfo) => {
    await addCsrf(page)
    await page.setViewportSize({ height: 800, width: 1280 })
    await page.route("**/api/v1/public/**", (route) => route.fulfill({
      contentType: "application/json",
      body: JSON.stringify(route.request().url().endsWith("/signing-session")
        ? { state: "STALE" }
        : { state: "OPEN", title: "변경된 행사" }),
    }))
    await page.goto(`/sign/${shareToken}`)
    await expect(page.getByTestId("public-signer-identify")).toBeVisible()
    await expect(page.getByTestId("signer-canvas")).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath("stale-1280.png"), fullPage: true })
  })
})
