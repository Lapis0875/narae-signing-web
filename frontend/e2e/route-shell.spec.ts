import { expect, type Page, test } from "@playwright/test";

type AuthSessionResponse =
  | { readonly authenticated: false; readonly expiresAt: null }
  | { readonly authenticated: true; readonly expiresAt: string };

type AuthSessionProbe = {
  readonly fulfilledBodies: AuthSessionResponse[];
  readonly requests: string[];
};

const authSessionPath = "/api/v1/auth/session";

async function mockApi(
  page: Page,
  sessionResponse: AuthSessionResponse | undefined,
  probe: AuthSessionProbe,
): Promise<void> {
  await page.route("**/api/v1/**", (route) => {
    if (new URL(route.request().url()).pathname === authSessionPath) {
      probe.requests.push(route.request().url());
      if (sessionResponse !== undefined) {
        probe.fulfilledBodies.push(sessionResponse);
        return route.fulfill({
          contentType: "application/json",
          body: JSON.stringify(sessionResponse),
        });
      }
    }

    return route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ status: "ready" }),
    });
  });
}

const routes = [
  { auth: "signed-out", path: "/login", title: "관리자 로그인" },
  { auth: "signed-in", path: "/boards", title: "보드 목록" },
  { auth: "signed-in", path: "/boards/new", title: "새 보드" },
  { auth: "signed-in", path: "/boards/board-1/edit", title: "보드 편집" },
  { auth: "signed-in", path: "/boards/board-1/full", title: "보드 전체보기" },
  { auth: "public", path: "/sign/share-1", title: "서명하기" },
] as const;

test.describe("@route-shell", () => {
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
