import { expect, type Page } from "@playwright/test";

type AuthSessionResponse =
  | { readonly authenticated: false; readonly expiresAt: null }
  | { readonly authenticated: true; readonly expiresAt: string };

export type AuthSessionProbe = {
  readonly fulfilledBodies: AuthSessionResponse[];
  readonly csrfTokens: string[];
  readonly identifyBodies: string[];
  readonly requests: string[];
  readonly signerRequests: string[];
  readonly unexpectedApiRequests: string[];
};

const authSessionPath = "/api/v1/auth/session";
export const boardId = "11111111-1111-4111-8111-111111111111";
const publicLinkPath = "/api/v1/public/links/share-1";
export const publicIdentifyPath = `${publicLinkPath}/identify`;
export const signingSessionPath = "/api/v1/public/signing-session";
export const signerIdentity = {
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

export async function mockApi(
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
    if (pathname === `${signingSessionPath}/draft` && request.method() === "PUT") {
      expect(request.postDataJSON()).toEqual({ strokes: [], version: 1 });
      expect(await request.headerValue("x-xsrf-token")).toBe("route-fixture-csrf");
      probe.signerRequests.push(`${request.method()} ${pathname}`);
      return route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ draftEpoch: 1, revision: 0 }),
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
