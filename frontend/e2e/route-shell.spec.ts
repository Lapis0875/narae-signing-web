import { expect, type Page, test } from "@playwright/test";

type AuthSessionResponse =
  | { readonly authenticated: false; readonly expiresAt: null }
  | { readonly authenticated: true; readonly expiresAt: string };

type AuthSessionProbe = {
  readonly fulfilledBodies: AuthSessionResponse[];
  readonly requests: string[];
};

const authSessionPath = "/api/v1/auth/session";
const boardId = "11111111-1111-4111-8111-111111111111";
const publicLinkPath = "/api/v1/public/links/share-1";
const signingSessionPath = "/api/v1/public/signing-session";
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
    if (pathname === authSessionPath) {
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
    if (pathname === signingSessionPath && request.method() === "GET") {
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ state: "READY" }),
      });
    }
    if (pathname === "/api/v1/admin/boards") {
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify([]),
      });
    }
    if (pathname === `/api/v1/admin/boards/${boardId}`) {
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify(boardDetail),
      });
    }
    if (pathname === `/api/v1/admin/boards/${boardId}/roster`) {
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify([]),
      });
    }
    if (pathname === `/api/v1/admin/boards/${boardId}/share`) {
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ shareToken: "share-1", version: 1 }),
      });
    }
    if (pathname === `/api/v1/admin/boards/${boardId}/background`) {
      return route.fulfill({ status: 204 });
    }
    if (pathname === `/api/v1/admin/boards/${boardId}/snapshot`) {
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
    const probe: AuthSessionProbe = { fulfilledBodies: [], requests: [] };
    await mockApi(page, { authenticated: false, expiresAt: null }, probe);
    await page.goto("/login");

    // When
    const status = await page.evaluate(async () => {
      return (await fetch("/api/v1/fixture-unknown")).status;
    });

    // Then
    expect(status).toBe(500);
  });

  for (const route of routes) {
    test(`renders ${route.path} from a mock API response`, async ({
      page,
    }, testInfo) => {
      // Given
      const probe: AuthSessionProbe = { fulfilledBodies: [], requests: [] };
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
        await expect(page.getByTestId("signer-canvas")).toBeVisible();
        await page.waitForLoadState("networkidle");
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
    const probe: AuthSessionProbe = { fulfilledBodies: [], requests: [] };
    await mockApi(page, undefined, probe);
    await page.setViewportSize({ height: 844, width: 390 });

    // When
    await page.goto("/sign/share-1");

    // Then
    await expect(page.getByTestId("unsupported-device-view")).toBeVisible();
    await expect(page.getByTestId("signer-canvas")).toHaveCount(0);
    expect(probe.requests).toHaveLength(0);
    expect(probe.fulfilledBodies).toHaveLength(0);
    await page.screenshot({
      path: testInfo.outputPath("phone-unsupported.png"),
    });
  });
});
