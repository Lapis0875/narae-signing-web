import { expect, test } from "@playwright/test";
import {
  type AuthSessionProbe,
  boardId,
  mockApi,
  publicIdentifyPath,
  signerIdentity,
  signingSessionPath,
} from "./routeShellApi.ts";

const routes = [
  { auth: "signed-out", path: "/login", title: "관리자 로그인" },
  { auth: "signed-in", path: "/boards", title: "보드 목록" },
  { auth: "signed-in", path: "/boards/new", title: "새 보드" },
  { auth: "signed-in", path: `/boards/${boardId}/edit`, title: "보드 편집" },
  {
    auth: "signed-in",
    path: `/boards/${boardId}/full`,
    title: "보드 전체보기",
  },
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
        await page
          .getByLabel("소속사 (선택)")
          .fill(signerIdentity.organization);
        await page.getByLabel("직책 (선택)").fill(signerIdentity.job);
        await page.getByLabel("이름").fill(signerIdentity.name);
        await page.getByRole("button", { name: "정보 확인" }).click();
        await expect(page.getByTestId("signer-canvas")).toBeVisible();
        await expect(
          page.locator(".public-signer__proportional-pad"),
        ).toBeVisible();
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
          `PUT ${signingSessionPath}/draft`,
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
