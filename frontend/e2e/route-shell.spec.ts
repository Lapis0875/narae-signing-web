import { expect, type Page, test } from "@playwright/test";

type AuthSessionResponse =
  | { readonly authenticated: false; readonly expiresAt: null }
  | { readonly authenticated: true; readonly expiresAt: string };

type AuthSessionProbe = {
  readonly fulfilledBodies: AuthSessionResponse[];
  readonly csrfTokens: string[];
  readonly identifyBodies: string[];
  readonly requests: string[];
  readonly signerRequests: string[];
  readonly unexpectedApiRequests: string[];
};

const authSessionPath = "/api/v1/auth/session";
const boardId = "11111111-1111-4111-8111-111111111111";
const publicLinkPath = "/api/v1/public/links/share-1";
const publicIdentifyPath = `${publicLinkPath}/identify`;
const signingSessionPath = "/api/v1/public/signing-session";
const signerIdentity = {
  organization: "나래미디어",
  job: "부장",
  name: "홍길동",
} as const;
const signingSessionResponse = {
  signatureAspectRatio: 1.777778,
  state: "READY",
} as const;
const boardDetail = {
  canvasHeight: 1080,
  canvasWidth: 1920,
  createdAt: "2026-08-23T00:00:00.000Z",
  id: boardId,
  shareLinkVersion: 1,
  status: "설정 중",
  title: "보드 편집",
  updatedAt: "2026-08-23T00:00:00.000Z",
};

async function mockApi(
  page: Page,
  sessionResponse: AuthSessionResponse | undefined,
  probe: AuthSessionProbe,
): Promise<void> {
  await page.context().addCookies([
    {
      name: "XSRF-TOKEN",
      url: "http://127.0.0.1:4173/",
      value: "route-fixture-csrf",
    },
  ]);
  await page.addInitScript(() => {
    class QaEventSource {
      onerror: (() => void) | null = null;
      onopen: (() => void) | null = null;

      addEventListener() {}
      close() {}
    }
    Object.defineProperty(window, "EventSource", {
      configurable: true,
      value: QaEventSource,
    });
  });
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    if (pathname === authSessionPath && request.method() === "GET") {
      probe.requests.push(route.request().url());
      if (sessionResponse !== undefined) {
        probe.fulfilledBodies.push(sessionResponse);
        return route.fulfill({
          contentType: "application/json",
          body: JSON.stringify(sessionResponse),
        });
      }
    }

    if (pathname === publicLinkPath && request.method() === "GET") {
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ state: "OPEN", title: "서명하기" }),
      });
    }
    if (pathname === publicIdentifyPath && request.method() === "POST") {
      probe.csrfTokens.push((await request.headerValue("x-xsrf-token")) ?? "");
      probe.identifyBodies.push(request.postData() ?? "");
      probe.signerRequests.push(`${request.method()} ${pathname}`);
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ identified: true }),
      });
    }
    if (pathname === signingSessionPath && request.method() === "GET") {
      probe.signerRequests.push(`${request.method()} ${pathname}`);
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify(signingSessionResponse),
      });
    }
    if (pathname === "/api/v1/admin/boards" && request.method() === "GET") {
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify([]),
      });
    }
    if (
      pathname === `/api/v1/admin/boards/${boardId}` &&
      request.method() === "GET"
    ) {
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify(boardDetail),
      });
    }
    if (
      pathname === `/api/v1/admin/boards/${boardId}/roster` &&
      request.method() === "GET"
    ) {
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify([]),
      });
    }
    if (
      pathname === `/api/v1/admin/boards/${boardId}/share` &&
      request.method() === "GET"
    ) {
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ shareToken: "share-1", version: 1 }),
      });
    }
    if (
      pathname === `/api/v1/admin/boards/${boardId}/background` &&
      request.method() === "GET"
    ) {
      return route.fulfill({ status: 204 });
    }
    if (
      pathname === `/api/v1/admin/boards/${boardId}/snapshot` &&
      request.method() === "GET"
    ) {
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({
          backgroundPresent: false,
          boardId,
          canvasHeight: boardDetail.canvasHeight,
          canvasWidth: boardDetail.canvasWidth,
          slots: [],
        }),
      });
    }
    probe.unexpectedApiRequests.push(`${request.method()} ${pathname}`);
    await route.fulfill({
      body: JSON.stringify({ code: "FIXTURE_UNEXPECTED_API" }),
      contentType: "application/json",
      status: 500,
    });
  });
}

const routes = [
  { auth: "signed-out", path: "/login", title: "관리자 로그인" },
  { auth: "signed-in", path: "/boards", title: "보드 목록" },
  { auth: "signed-in", path: "/boards/new", title: "새 보드" },
  { auth: "signed-in", path: `/boards/${boardId}/edit`, title: "보드 편집" },
  { auth: "signed-in", path: `/boards/${boardId}/full`, title: "보드 전체보기" },
  { auth: "public", path: "/sign/share-1", title: "서명하기" },
] as const;

test.describe("@route-shell", () => {
  test("fails closed for an unknown API request", async ({ page }) => {
    // Given
    const probe: AuthSessionProbe = {
      csrfTokens: [],
      fulfilledBodies: [],
      identifyBodies: [],
      requests: [],
      signerRequests: [],
      unexpectedApiRequests: [],
    };
    await mockApi(page, { authenticated: false, expiresAt: null }, probe);
    await page.goto("/login");

    // When
    const status = await page.evaluate(async () => {
      return (await fetch("/api/v1/fixture-unknown")).status;
    });

    // Then
    expect(status).toBe(500);
    expect(probe.unexpectedApiRequests).toEqual([
      "GET /api/v1/fixture-unknown",
    ]);
  });

  test("fails closed for a wrong method on a known API request", async ({
    page,
  }) => {
    // Given
    const probe: AuthSessionProbe = {
      csrfTokens: [],
      fulfilledBodies: [],
      identifyBodies: [],
      requests: [],
      signerRequests: [],
      unexpectedApiRequests: [],
    };
    await mockApi(page, { authenticated: false, expiresAt: null }, probe);
    await page.goto("/login");

    // When
    const status = await page.evaluate(async (path) => {
      return (await fetch(path, { method: "POST" })).status;
    }, `/api/v1/admin/boards/${boardId}/snapshot`);

    // Then
    expect(status).toBe(500);
    expect(probe.unexpectedApiRequests).toEqual([
      `POST /api/v1/admin/boards/${boardId}/snapshot`,
    ]);
  });

  for (const route of routes) {
    test(`renders ${route.path} from a mock API response`, async ({
      page,
    }, testInfo) => {
      // Given
      const probe: AuthSessionProbe = {
        csrfTokens: [],
        fulfilledBodies: [],
        identifyBodies: [],
        requests: [],
        signerRequests: [],
        unexpectedApiRequests: [],
      };
      const consoleErrors: string[] = [];
      const pageErrors: string[] = [];
      page.on("console", (message) => {
        if (message.type() === "error") {
          consoleErrors.push(message.text());
        }
      });
      page.on("pageerror", (error) => pageErrors.push(error.message));
      const sessionResponse =
        route.auth === "signed-out"
          ? { authenticated: false, expiresAt: null }
          : route.auth === "signed-in"
            ? {
                authenticated: true,
                expiresAt: new Date(Date.now() + 60_000).toISOString(),
              }
            : undefined;
      await mockApi(page, sessionResponse, probe);

      // When
      await page.goto(route.path);

      // Then
      await expect(
        page.getByRole("heading", { level: 1, name: route.title }),
      ).toBeVisible();
      if (route.auth === "public") {
        await page.getByLabel("소속사 (선택)").fill(signerIdentity.organization);
        await page.getByLabel("직책 (선택)").fill(signerIdentity.job);
        await page.getByLabel("이름").fill(signerIdentity.name);
        await page.getByRole("button", { name: "정보 확인" }).click();
        await expect(page.getByTestId("signer-canvas")).toBeVisible();
        await expect(page.locator(".public-signer__proportional-pad")).toBeVisible();
        await page.waitForLoadState("networkidle");
        await page.evaluate(async () => {
          await new Promise<void>((resolve) =>
            requestAnimationFrame(() =>
              requestAnimationFrame(() => requestAnimationFrame(resolve)),
            ),
          );
        });
      }
      if (route.auth === "signed-out") {
        expect(probe.requests).toHaveLength(1);
        expect(probe.fulfilledBodies).toEqual([
          { authenticated: false, expiresAt: null },
        ]);
      } else if (route.auth === "signed-in") {
        expect(probe.requests).toHaveLength(1);
        expect(probe.fulfilledBodies).toHaveLength(1);
        expect(probe.fulfilledBodies[0]).toMatchObject({ authenticated: true });
        expect(
          Date.parse(probe.fulfilledBodies[0]?.expiresAt ?? ""),
        ).toBeGreaterThan(Date.now());
      } else {
        expect(probe.requests).toHaveLength(0);
        expect(probe.fulfilledBodies).toHaveLength(0);
        expect(probe.csrfTokens).toEqual(["route-fixture-csrf"]);
        expect(probe.identifyBodies).toEqual([JSON.stringify(signerIdentity)]);
        expect(probe.signerRequests).toEqual([
          `POST ${publicIdentifyPath}`,
          `GET ${signingSessionPath}`,
        ]);
      }
      expect(probe.unexpectedApiRequests).toEqual([]);
      if (route.auth === "public") {
        expect(consoleErrors).toEqual([]);
        expect(pageErrors).toEqual([]);
      }
      await page.screenshot({
        path: testInfo.outputPath(
          `${route.path.replaceAll("/", "-") || "root"}.png`,
        ),
      });
    });
  }

  test("keeps the signer canvas unmounted on a phone viewport", async ({
    page,
  }, testInfo) => {
    // Given
    const probe: AuthSessionProbe = {
      csrfTokens: [],
      fulfilledBodies: [],
      identifyBodies: [],
      requests: [],
      signerRequests: [],
      unexpectedApiRequests: [],
    };
    await mockApi(page, undefined, probe);
    await page.setViewportSize({ height: 844, width: 390 });

    // When
    await page.goto("/sign/share-1");

    // Then
    await expect(page.getByTestId("unsupported-device-view")).toBeVisible();
    await expect(page.getByTestId("signer-canvas")).toHaveCount(0);
    expect(probe.requests).toHaveLength(0);
    expect(probe.fulfilledBodies).toHaveLength(0);
    expect(probe.unexpectedApiRequests).toEqual([]);
    await page.screenshot({
      path: testInfo.outputPath("phone-unsupported.png"),
    });
  });
});
