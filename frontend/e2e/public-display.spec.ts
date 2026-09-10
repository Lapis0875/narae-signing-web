import { writeFile } from "node:fs/promises";
import {
  type BrowserContext,
  expect,
  type Route,
  test,
} from "@playwright/test";
import type { FullViewSlot } from "../src/features/boards/fullview/fullViewApi.ts";
import type { SignaturePayload } from "../src/features/signing/pad/signaturePayload.ts";

const boardId = "00000000-0000-4000-8000-000000000011";
const slotId = "00000000-0000-4000-8000-000000000012";
const otherSlotId = "00000000-0000-4000-8000-000000000013";
const shareToken = "public-display-test-token";
const viewports = [
  { height: 812, label: "375x812", width: 375 },
  { height: 1024, label: "768x1024", width: 768 },
  { height: 800, label: "1280x800", width: 1280 },
] as const;

for (const viewport of viewports) {
  test(`public display incrementally reconciles live drafts at ${viewport.label}`, async ({
    page,
  }, testInfo) => {
    let snapshotRequests = 0;
    const browserErrors: string[] = [];
    const livePayload = {
      version: 1,
      strokes: [
        {
          points: [
            { x: 100_000, y: 200_000 },
            { x: 500_000, y: 500_000 },
            { x: 900_000, y: 800_000 },
          ],
        },
      ],
    } satisfies SignaturePayload;
    let serverSlot: Pick<
      FullViewSlot,
      "draftEpoch" | "draftSignature" | "revision" | "signature"
    > = {
      draftEpoch: 1,
      draftSignature: { version: 1, strokes: [] },
      revision: 0,
      signature: null,
    };

    await page.setViewportSize(viewport);
    page.on("console", (message) => {
      if (message.type() === "error") browserErrors.push(message.text());
    });
    page.on("pageerror", (error) => browserErrors.push(error.message));
    await page.addInitScript(() => {
      class QaEventSource {
        closed = false;
        onerror: (() => void) | null = null;
        onopen: (() => void) | null = null;
        readonly listeners = new Map<string, EventListener>();

        constructor() {
          window.addEventListener("qa-public-display-event", (event) => {
            if (
              this.closed ||
              !(event instanceof CustomEvent) ||
              typeof event.detail !== "object" ||
              event.detail === null
            )
              return;
            const type = Reflect.get(event.detail, "type");
            const data = Reflect.get(event.detail, "data");
            if (typeof type === "string")
              this.listeners.get(type)?.(
                typeof data === "string"
                  ? new MessageEvent(type, { data })
                  : new Event(type),
              );
          });
          queueMicrotask(() => {
            if (!this.closed) this.onopen?.();
          });
        }

        addEventListener(
          type: string,
          listener: EventListenerOrEventListenerObject,
        ) {
          if (typeof listener === "function")
            this.listeners.set(type, listener);
        }

        close() {
          this.closed = true;
        }
      }
      Object.defineProperty(window, "EventSource", {
        configurable: true,
        value: QaEventSource,
      });
    });
    await page.route(`**/api/v1/public/links/${shareToken}`, (route) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ state: "OPEN", title: "행사장 공개 화면" }),
      }),
    );
    await page.route(
      `**/api/v1/public/links/${shareToken}/display/claim`,
      (route) => route.fulfill({ status: 204 }),
    );
    await page.route(
      `**/api/v1/public/links/${shareToken}/display/heartbeat`,
      (route) => route.fulfill({ status: 204 }),
    );
    await page.route(
      `**/api/v1/public/links/${shareToken}/display/release`,
      (route) => route.fulfill({ status: 204 }),
    );
    await page.route(
      `**/api/v1/public/links/${shareToken}/display/title`,
      (route) =>
        route.fulfill({
          contentType: "application/json",
          body: JSON.stringify({ title: "행사장 공개 화면" }),
        }),
    );
    await page.route(
      `**/api/v1/public/links/${shareToken}/display/background`,
      (route) => route.fulfill({ status: 204 }),
    );
    await page.route(
      `**/api/v1/public/links/${shareToken}/display/snapshot`,
      (route) => {
        snapshotRequests += 1;
        return route.fulfill({
          contentType: "application/json",
          body: JSON.stringify({
            backgroundPresent: false,
            boardId,
            canvasHeight: 600,
            canvasWidth: 800,
            signatureInkColor: "black",
            slots: [
              {
                ...serverSlot,
                height: 0.25,
                id: slotId,
                width: 0.4,
                x: 0.2,
                y: 0.3,
              },
              {
                draftEpoch: 1,
                draftSignature: null,
                height: 0.2,
                id: otherSlotId,
                revision: 0,
                signature: {
                  version: 1,
                  strokes: [
                    {
                      points: [
                        { x: 200_000, y: 800_000 },
                        { x: 800_000, y: 200_000 },
                      ],
                    },
                  ],
                },
                width: 0.25,
                x: 0.65,
                y: 0.1,
              },
            ],
          }),
        });
      },
    );

    await page.goto(`/display/${shareToken}`);
    await expect(
      page.getByRole("heading", { name: "행사장 공개 화면" }),
    ).toBeVisible();
    const board = page.getByTestId("full-view-canvas");
    await expect(board).toBeVisible();
    const boardBounds = await board.boundingBox();
    if (boardBounds === null)
      throw new Error("Public board bounds are unavailable");
    expect(boardBounds.y + boardBounds.height).toBeLessThanOrEqual(
      viewport.height,
    );
    expect(
      Math.abs(boardBounds.width / boardBounds.height - 800 / 600),
    ).toBeLessThan(0.01);
    const target = page.locator(`[data-slot-id="${slotId}"] canvas`);
    const other = page.locator(`[data-slot-id="${otherSlotId}"] canvas`);
    await expect(target).toBeVisible();
    await expect(other).toBeVisible();
    const targetBefore = await target.evaluate((canvas) =>
      canvas instanceof HTMLCanvasElement ? canvas.toDataURL() : "",
    );
    const otherBefore = await other.evaluate((canvas) =>
      canvas instanceof HTMLCanvasElement ? canvas.toDataURL() : "",
    );
    const beforeDraft = snapshotRequests;
    await page.evaluate(() =>
      window.dispatchEvent(
        new CustomEvent("qa-public-display-event", {
          detail: {
            data: JSON.stringify({
              draftEpoch: 1,
              operation: "begin",
              points: [{ x: 100_000, y: 200_000 }],
              revision: 1,
              slotId: "00000000-0000-4000-8000-000000000012",
              strokeIndex: 0,
            }),
            type: "signature-draft",
          },
        }),
      ),
    );
    await expect
      .poll(() =>
        target.evaluate((canvas) =>
          canvas instanceof HTMLCanvasElement ? canvas.toDataURL() : "",
        ),
      )
      .not.toBe(targetBefore);
    expect(
      await other.evaluate((canvas) =>
        canvas instanceof HTMLCanvasElement ? canvas.toDataURL() : "",
      ),
    ).toBe(otherBefore);
    expect(snapshotRequests).toBe(beforeDraft);

    await page.evaluate(() =>
      window.dispatchEvent(
        new CustomEvent("qa-public-display-event", {
          detail: {
            data: JSON.stringify({
              draftEpoch: 1,
              operation: "append",
              points: [{ x: 500_000, y: 500_000 }],
              revision: 2,
              slotId: "00000000-0000-4000-8000-000000000012",
              strokeIndex: 0,
            }),
            type: "signature-draft",
          },
        }),
      ),
    );
    const afterRevision2 = await target.evaluate((canvas) =>
      canvas instanceof HTMLCanvasElement ? canvas.toDataURL() : "",
    );
    await page.evaluate(() =>
      window.dispatchEvent(
        new CustomEvent("qa-public-display-event", {
          detail: {
            data: JSON.stringify({
              draftEpoch: 1,
              operation: "append",
              points: [{ x: 500_000, y: 500_000 }],
              revision: 2,
              slotId: "00000000-0000-4000-8000-000000000012",
              strokeIndex: 0,
            }),
            type: "signature-draft",
          },
        }),
      ),
    );
    expect(
      await target.evaluate((canvas) =>
        canvas instanceof HTMLCanvasElement ? canvas.toDataURL() : "",
      ),
    ).toBe(afterRevision2);

    serverSlot = {
      draftEpoch: 1,
      draftSignature: livePayload,
      revision: 4,
      signature: null,
    };
    const beforeGap = snapshotRequests;
    await page.evaluate(() =>
      window.dispatchEvent(
        new CustomEvent("qa-public-display-event", {
          detail: {
            data: JSON.stringify({
              draftEpoch: 1,
              operation: "append",
              points: [{ x: 900_000, y: 800_000 }],
              revision: 4,
              slotId: "00000000-0000-4000-8000-000000000012",
              strokeIndex: 0,
            }),
            type: "signature-draft",
          },
        }),
      ),
    );
    await expect.poll(() => snapshotRequests).toBeGreaterThan(beforeGap);
    await expect
      .poll(() =>
        target.evaluate((canvas) =>
          canvas instanceof HTMLCanvasElement ? canvas.toDataURL() : "",
        ),
      )
      .not.toBe(afterRevision2);
    const recoveredGeometry = await target.evaluate((canvas) =>
      canvas instanceof HTMLCanvasElement ? canvas.toDataURL() : "",
    );
    await page.screenshot({
      path: `../.omo/evidence/public-display-live-ink/task-6-${testInfo.project.name}-${viewport.label}-live.png`,
    });

    serverSlot = {
      draftEpoch: 1,
      draftSignature: { version: 1, strokes: [] },
      revision: 3,
      signature: null,
    };
    await page.evaluate(() =>
      window.dispatchEvent(
        new CustomEvent("qa-public-display-event", {
          detail: { type: "board-updated" },
        }),
      ),
    );
    await expect.poll(() => snapshotRequests).toBeGreaterThan(beforeGap + 1);
    expect(
      await target.evaluate((canvas) =>
        canvas instanceof HTMLCanvasElement ? canvas.toDataURL() : "",
      ),
    ).toBe(recoveredGeometry);

    serverSlot = {
      draftEpoch: 2,
      draftSignature: null,
      revision: 0,
      signature: livePayload,
    };
    await page.evaluate(() =>
      window.dispatchEvent(
        new CustomEvent("qa-public-display-event", {
          detail: {
            data: JSON.stringify({
              draftEpoch: 2,
              operation: "clear",
              points: [],
              revision: 0,
              signature: null,
              slotId: "00000000-0000-4000-8000-000000000012",
              strokeIndex: -1,
            }),
            type: "signature-draft",
          },
        }),
      ),
    );
    await expect(target).toHaveCount(0);
    await page.evaluate(() =>
      window.dispatchEvent(
        new CustomEvent("qa-public-display-event", {
          detail: { type: "signature-submitted" },
        }),
      ),
    );
    await expect(target).toBeVisible();
    expect(
      await target.evaluate((canvas) =>
        canvas instanceof HTMLCanvasElement ? canvas.toDataURL() : "",
      ),
    ).toBe(recoveredGeometry);

    await expect(page.getByRole("button")).toHaveCount(0);
    await expect(page.getByRole("link")).toHaveCount(0);
    await expect(page.locator("body")).not.toContainText("홍길동");
    expect(
      await page.evaluate(() => ({
        horizontal:
          document.documentElement.scrollWidth <=
          document.documentElement.clientWidth,
        vertical:
          document.documentElement.scrollHeight <=
          document.documentElement.clientHeight,
      })),
    ).toEqual({ horizontal: true, vertical: true });
    await page.screenshot({
      path: `../.omo/evidence/public-display-live-ink/task-6-${testInfo.project.name}-${viewport.label}-final.png`,
    });
    await writeFile(
      `../.omo/evidence/public-display-live-ink/task-6-${testInfo.project.name}-${viewport.label}-network.json`,
      JSON.stringify(
        {
          beforeDraft,
          beforeGap,
          boardBounds,
          finalSnapshotRequests: snapshotRequests,
          sequence: [1, 2, 2, 4],
          viewport,
        },
        null,
        2,
      ),
    );
    expect(browserErrors).toEqual([]);
  });
}

test("claim gates content, reload reconnects, outages retain the board, and replacement is terminal", async ({
  baseURL,
  browser,
}, testInfo) => {
  // Given
  let activeOwner: string | null = null;
  let nextOwner = 1;
  let transportDown = false;
  const requests: string[] = [];
  const contentRequests = new Map<string, number>();

  const configure = async (
    context: BrowserContext,
    label: string,
  ): Promise<void> => {
    contentRequests.set(label, 0);
    await context.route(`**/api/v1/public/links/${shareToken}`, (route) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ state: "OPEN", title: "행사장 공개 화면" }),
      }),
    );
    await context.addInitScript(() => {
      class QaEventSource {
        closed = false;
        onerror: (() => void) | null = null;
        onopen: (() => void) | null = null;
        readonly listeners = new Map<string, EventListener>();

        constructor() {
          window.addEventListener("qa-public-display-event", (event) => {
            if (
              this.closed ||
              !(event instanceof CustomEvent) ||
              typeof event.detail !== "object" ||
              event.detail === null
            )
              return;
            const type = Reflect.get(event.detail, "type");
            if (typeof type === "string") this.listeners.get(type)?.(event);
          });
          window.addEventListener("qa-public-display-error", () =>
            this.onerror?.(),
          );
          queueMicrotask(() => {
            if (!this.closed) this.onopen?.();
          });
        }

        addEventListener(
          type: string,
          listener: EventListenerOrEventListenerObject,
        ) {
          if (typeof listener === "function")
            this.listeners.set(type, listener);
        }

        close() {
          this.closed = true;
        }
      }
      Object.defineProperty(window, "EventSource", {
        configurable: true,
        value: QaEventSource,
      });
    });
    await context.route(
      `**/api/v1/public/links/${shareToken}/display/**`,
      async (route: Route) => {
        const request = route.request();
        const path = new URL(request.url()).pathname;
        const suffix = path.slice(path.lastIndexOf("/") + 1);
        const cookie = (await context.cookies(request.url()))
          .map(({ name, value }) => `${name}=${value}`)
          .join("; ");
        requests.push(`${label}:${request.method()}:${suffix}:${cookie}`);
        if (suffix === "claim") {
          if (
            activeOwner !== null &&
            !cookie.includes(`public_display=${activeOwner}`)
          ) {
            await route.fulfill({
              contentType: "application/json",
              body: JSON.stringify({
                code: "DISPLAY_ALREADY_CONNECTED",
                message: "다른 화면에서 이미 보드를 표시하고 있습니다.",
              }),
              status: 409,
            });
            return;
          }
          if (activeOwner === null) {
            activeOwner = `owner-${nextOwner}`;
            nextOwner += 1;
          }
          await route.fulfill({
            headers: {
              "set-cookie": `public_display=${activeOwner}; Path=/api/v1/public/links; HttpOnly; SameSite=Lax`,
            },
            status: 204,
          });
          return;
        }
        if (suffix === "heartbeat" || suffix === "release") {
          await route.fulfill({ status: 204 });
          return;
        }
        contentRequests.set(label, (contentRequests.get(label) ?? 0) + 1);
        if (
          transportDown &&
          (suffix === "snapshot" || suffix === "background")
        ) {
          await route.abort("failed");
          return;
        }
        if (suffix === "title") {
          await route.fulfill({
            contentType: "application/json",
            body: JSON.stringify({ title: "행사장 공개 화면" }),
          });
          return;
        }
        if (suffix === "background") {
          await route.fulfill({ status: 204 });
          return;
        }
        await route.fulfill({
          contentType: "application/json",
          body: JSON.stringify({
            backgroundPresent: false,
            boardId,
            canvasHeight: 600,
            canvasWidth: 800,
            signatureInkColor: "black",
            slots: [],
          }),
        });
      },
    );
  };

  const origin = new URL(baseURL ?? "http://127.0.0.1:4173").origin;
  const firstContext = await browser.newContext({ baseURL: origin });
  const secondContext = await browser.newContext({ baseURL: origin });
  await configure(firstContext, "first");
  await configure(secondContext, "second");
  const first = await firstContext.newPage();
  const second = await secondContext.newPage();

  try {
    // When: the first context claims, then reloads with the same cookie.
    await first.goto(`/display/${shareToken}`);
    await expect(first.getByTestId("full-view-canvas")).toBeVisible();
    await first.reload();
    await expect(first.getByTestId("full-view-canvas")).toBeVisible();

    // Then
    expect(
      requests.filter((request) => request.startsWith("first:POST:claim:")),
    ).toEqual(["first:POST:claim:", "first:POST:claim:public_display=owner-1"]);

    // When
    await second.goto(`/display/${shareToken}`);

    // Then
    await expect(second.getByRole("alert")).toHaveText(
      "다른 화면에서 이미 보드를 표시하고 있습니다.",
    );
    await expect(second.getByRole("heading")).toHaveCount(0);
    await expect(second.getByTestId("full-view-canvas")).toHaveCount(0);
    expect(contentRequests.get("second")).toBe(0);
    await second.screenshot({
      path: "../.omo/evidence/public-display-live-ink/task-5-denial.png",
    });

    // When
    await first.evaluate(() =>
      window.dispatchEvent(new Event("qa-public-display-error")),
    );
    transportDown = true;
    const beforeOutage = requests.length;
    await first.evaluate(() =>
      window.dispatchEvent(
        new CustomEvent("qa-public-display-event", {
          detail: { type: "board-updated" },
        }),
      ),
    );

    // Then
    await expect.poll(() => requests.length).toBeGreaterThan(beforeOutage);
    await expect(first.getByTestId("full-view-canvas")).toBeVisible();
    await expect(first.getByRole("alert")).toHaveCount(0);
    await first.screenshot({
      path: "../.omo/evidence/public-display-live-ink/task-5-transport-retained.png",
    });

    // When
    await first.evaluate(() =>
      window.dispatchEvent(
        new CustomEvent("qa-public-display-event", {
          detail: { type: "display-replaced" },
        }),
      ),
    );

    // Then
    await expect(first.getByRole("alert")).toHaveText(
      "이 화면의 표시 연결이 다른 화면으로 전환되었습니다.",
    );
    await expect(first.getByTestId("full-view-canvas")).toHaveCount(0);
    await first.screenshot({
      path: "../.omo/evidence/public-display-live-ink/task-5-replaced.png",
    });
    await testInfo.attach("network-ordering", {
      body: JSON.stringify(
        { contentRequests: Object.fromEntries(contentRequests), requests },
        null,
        2,
      ),
      contentType: "application/json",
    });
  } finally {
    await firstContext.close();
    await secondContext.close();
  }
});
