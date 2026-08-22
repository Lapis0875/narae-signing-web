import { chromium, expect, test } from "@playwright/test";

type ApiMode = "generic-error" | "loading" | "ready";

const authSessionPath = "/api/v1/auth/session";
const publicLinkPath = "/api/v1/public/links/share-1";
const signingSessionPath = "/api/v1/public/signing-session";

async function settleVisualFrame(page: import("@playwright/test").Page) {
  await page.evaluate(async () => {
    await document.fonts.ready;
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    );
  });
}

test("captures every responsive visual-foundation state", async ({
  browserName,
}, testInfo) => {
  test.skip(
    browserName !== "chromium",
    "Retail Chrome evidence runs only in the Chromium project.",
  );
  const browser = await chromium.launch({ channel: "chrome" });
  const context = await browser.newContext({
    baseURL: testInfo.project.use.baseURL,
  });
  const page = await context.newPage();

  try {
    // Given
    let apiMode: ApiMode = "ready";
    let releaseLoading: (() => void) | undefined;
    const consoleErrors: string[] = [];
    const pageErrors: string[] = [];
    const externalRequests: string[] = [];

    page.on("console", (message) => {
      if (message.type() === "error") {
        consoleErrors.push(message.text());
      }
    });
    page.on("pageerror", (error) => pageErrors.push(error.message));
    page.on("request", (request) => {
      const requestUrl = new URL(request.url());
      if (requestUrl.hostname !== "127.0.0.1") {
        externalRequests.push(request.url());
      }
    });
    await page.addInitScript(() => {
      const nativeFetch = window.fetch.bind(window);
      window.fetch = async (input, init) => {
        const requestUrl =
          input instanceof Request ? input.url : input.toString();
        if (
          requestUrl.includes("/api/v1/") &&
          window.localStorage.getItem("visual-api-mode") === "forbidden"
        ) {
          return new Response(JSON.stringify({ code: "FORBIDDEN" }), {
            headers: { "content-type": "application/json" },
            status: 403,
          });
        }
        return nativeFetch(input, init);
      };
    });
    await page.route("**/api/v1/**", async (route) => {
      const pathname = new URL(route.request().url()).pathname;
      if (apiMode === "loading") {
        await new Promise<void>((resolve) => {
          releaseLoading = resolve;
        });
      }
      if (pathname === authSessionPath && apiMode === "generic-error") {
        await route.fulfill({
          body: JSON.stringify({}),
          contentType: "application/json",
        });
        return;
      }
      if (pathname === authSessionPath) {
        await route.fulfill({
          body: JSON.stringify({ authenticated: false, expiresAt: null }),
          contentType: "application/json",
        });
        return;
      }
      if (pathname === publicLinkPath) {
        await route.fulfill({
          body: JSON.stringify({ state: "OPEN", title: "서명하기" }),
          contentType: "application/json",
        });
        return;
      }
      if (pathname === signingSessionPath) {
        await route.fulfill({
          body: JSON.stringify({ state: "READY" }),
          contentType: "application/json",
        });
        return;
      }
      await route.continue();
    });

    // When: desktop login receives keyboard focus.
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto("/login");
    await expect(page.getByLabel("이메일")).toBeVisible();
    await page.keyboard.press("Tab");
    await page.keyboard.press("Tab");

    // Then
    await expect(page.getByLabel("이메일")).toBeFocused();
    await expect(page.getByLabel("이메일")).toHaveCSS("outline-style", "solid");
    await settleVisualFrame(page);
    await page.screenshot({
      path: testInfo.outputPath("login-focused-1280x800.png"),
    });

    // When: session request remains pending.
    apiMode = "loading";
    await page.goto("/login");

    // Then
    await expect(page.getByTestId("loading-view")).toBeVisible();
    await settleVisualFrame(page);
    await page.screenshot({
      path: testInfo.outputPath("loading-1280x800.png"),
    });
    releaseLoading?.();
    apiMode = "ready";
    await expect(page.getByLabel("이메일")).toBeVisible();

    // When: session endpoint forbids access.
    await page.evaluate(() =>
      window.localStorage.setItem("visual-api-mode", "forbidden"),
    );
    await page.goto("/login");

    // Then
    await expect(page.getByTestId("forbidden-view")).toBeVisible();
    await settleVisualFrame(page);
    await page.screenshot({
      path: testInfo.outputPath("forbidden-1280x800.png"),
    });
    await page.evaluate(() =>
      window.localStorage.removeItem("visual-api-mode"),
    );

    // When: session endpoint has a generic failure.
    apiMode = "generic-error";
    await page.goto("/login");

    // Then
    await expect(page.getByTestId("error-view")).toBeVisible();
    await settleVisualFrame(page);
    await page.screenshot({ path: testInfo.outputPath("error-1280x800.png") });

    // When: representative public RouteShell resolves.
    apiMode = "ready";
    await page.goto("/sign/share-1");

    // Then
    await expect(page.locator(".public-signer__intro")).toBeVisible();
    await expect(
      page.getByRole("heading", { level: 1, name: "서명하기" }),
    ).toBeVisible();
    await settleVisualFrame(page);
    await page.screenshot({
      path: testInfo.outputPath("route-panel-1280x800.png"),
    });

    // When: phone viewport opens public signing.
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto("/sign/share-1");

    // Then
    await expect(page.getByTestId("unsupported-device-view")).toBeVisible();
    await expect(page.getByTestId("signer-canvas")).toHaveCount(0);
    await settleVisualFrame(page);
    await page.screenshot({
      path: testInfo.outputPath("unsupported-375x812.png"),
    });

    // When: tablet viewport opens public signing.
    await page.setViewportSize({ width: 768, height: 1024 });
    await page.goto("/sign/share-1");

    // Then
    await expect(page.getByTestId("unsupported-device-view")).toHaveCount(0);
    await expect(page.getByTestId("signer-canvas")).toBeVisible();
    await settleVisualFrame(page);
    await page.screenshot({ path: testInfo.outputPath("signer-768x1024.png") });
    expect(consoleErrors).toEqual([
      "api_request_failed {code: FORBIDDEN, method: GET, requestId: null, route: /api/v1/auth/session, status: 403}",
      "api_request_failed {code: MALFORMED_RESPONSE, method: GET, requestId: null, route: /api/v1/auth/session, status: 200}",
    ]);
    expect(pageErrors).toEqual([]);
    expect(externalRequests).toEqual([]);
  } finally {
    try {
      await context.close();
    } finally {
      await browser.close();
    }
  }
});
