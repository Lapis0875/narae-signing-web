import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import {
  expect,
  type Locator,
  type Page,
  type Route,
  test,
} from "@playwright/test";
import encodeQR, { Bitmap } from "qr";
import decodeQR from "qr/decode.js";

const boardId = "11111111-1111-4111-8111-111111111111";
const slotOne = "21111111-1111-4111-8111-111111111111",
  slotTwo = "22222222-2222-4222-8222-222222222222";
const evidenceDir =
  process.env.TASK10_EVIDENCE_DIR ??
  process.env.TASK2_EVIDENCE_DIR ??
  process.env.TASK23_EVIDENCE_DIR ??
  path.resolve("../.omo/evidence/task-23-admin-canvas-ui/browser");
const now = "2026-08-21T00:00:00.000Z";

function rosterEntry(id: string, slotId: string, name: string) {
  return {
    id,
    identity: { job: "담당", name, organization: "나래" },
    slot: {
      height: null,
      id: slotId,
      placementStatus: "UNPLACED",
      revision: 0,
      width: null,
      x: null,
      y: null,
    },
    submitted: false,
  };
}

async function syntheticPng(page: Page, first: string, second: string) {
  return Buffer.from(
    await page.evaluate(
      ([start, end]) => {
        const canvas = document.createElement("canvas");
        canvas.width = 1200;
        canvas.height = 800;
        const context = canvas.getContext("2d");
        if (context === null) throw new Error("Synthetic canvas unavailable");
        const gradient = context.createLinearGradient(
          0,
          0,
          canvas.width,
          canvas.height,
        );
        gradient.addColorStop(0, start);
        gradient.addColorStop(1, end);
        context.fillStyle = gradient;
        context.fillRect(0, 0, canvas.width, canvas.height);
        return canvas.toDataURL("image/png").split(",").at(1) ?? "";
      },
      [first, second],
    ),
    "base64",
  );
}

type CaptureOptions = {
  readonly anchor?: Locator;
  readonly dialog?: boolean;
  readonly scrollOffset?: number;
};

function fractionDigits(value: number) {
  return (String(value).split(".").at(1) ?? "").length;
}

async function captureState(
  page: Page,
  state: string,
  options: CaptureOptions = {},
) {
  for (const width of [375, 768, 1280]) {
    const height = width === 1280 ? 800 : 812;
    await page.setViewportSize({ width, height });
    if (options.anchor !== undefined)
      await options.anchor.scrollIntoViewIfNeeded();
    if (options.scrollOffset !== undefined)
      await page.evaluate(
        (offset) => window.scrollBy(0, offset),
        options.scrollOffset,
      );
    const metadata = await page.evaluate(() => ({
      captureMode: "viewport",
      documentHeight: document.documentElement.scrollHeight,
      focus:
        document.activeElement?.getAttribute("aria-label") ??
        document.activeElement?.tagName ??
        null,
      openDialogCount: document.querySelectorAll("dialog[open]").length,
      scrollX: window.scrollX,
      scrollY: window.scrollY,
      stickyHeaderCount: document.querySelectorAll(".app-header").length,
      stickyHeaderTop:
        document.querySelector(".app-header")?.getBoundingClientRect().top ??
        null,
    }));
    expect(metadata.stickyHeaderCount).toBe(1);
    expect(metadata.stickyHeaderTop).toBe(0);
    expect(metadata.openDialogCount).toBe(options.dialog === true ? 1 : 0);
    if (state === "authoritative-409-recovery" && width === 375) {
      const geometry = await page
        .locator(".slot-overlay")
        .evaluateAll((slots) =>
          slots.map((slot) => {
            const move = slot.querySelector(".slot-move"),
              resize = slot.querySelector(".slot-resize"),
              unplace = slot.querySelector(".slot-actions button");
            if (move === null || resize === null || unplace === null)
              throw new Error("Missing slot action");
            const rectangle = (element: Element) => {
              const bounds = element.getBoundingClientRect();
              return {
                bottom: bounds.bottom,
                height: bounds.height,
                left: bounds.left,
                right: bounds.right,
                top: bounds.top,
                width: bounds.width,
              };
            };
            return {
              move: rectangle(move),
              resize: rectangle(resize),
              unplace: rectangle(unplace),
            };
          }),
        );
      expect(
        geometry.every(
          ({ move, resize, unplace }) =>
            move.height === 44 &&
            unplace.height === 44 &&
            resize.height === 44 &&
            move.bottom <= unplace.top &&
            unplace.bottom <= resize.top,
        ),
      ).toBe(true);
      await writeFile(
        path.join(evidenceDir, "authoritative-409-geometry-375.json"),
        `${JSON.stringify(geometry, null, 2)}\n`,
      );
    }
    const stem = `${state}-${width}`;
    await page.screenshot({
      fullPage: false,
      path: path.join(evidenceDir, `${stem}.png`),
    });
    await writeFile(
      path.join(evidenceDir, `${stem}.json`),
      `${JSON.stringify({ height, state, viewport: width, ...metadata }, null, 2)}\n`,
    );
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

test("@admin-editor edits the authoritative canvas and recovers failed mutations", async ({
  baseURL,
  browserName,
  context,
  page,
}) => {
  // Given
  await mkdir(evidenceDir, { recursive: true });
  const origin = (baseURL ?? "http://127.0.0.1:4173").replace(/\/$/u, "");
  if (browserName === "chromium")
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  else
    await context.addInitScript(() =>
      Object.defineProperty(navigator, "clipboard", {
        configurable: true,
        value: {
          writeText: (value: string) => {
            document.documentElement.dataset.task23ClipboardWrite = value;
            return Promise.resolve();
          },
        },
      }),
    );
  await context.addCookies([
    { name: "XSRF-TOKEN", value: "csrf-redacted", url: origin },
  ]);
  let board = {
    canvasHeight: 1080,
    canvasWidth: 1920,
    createdAt: now,
    id: boardId,
    shareLinkVersion: 1,
    signatureInkColor: "black",
    status: "설정 중",
    title: "가을 서명 발표회",
    updatedAt: now,
  };
  let roster = [
    rosterEntry("31111111-1111-4111-8111-111111111111", slotOne, "한별"),
    rosterEntry("32222222-2222-4222-8222-222222222222", slotTwo, "누리"),
  ];
  let share = { shareToken: "safe-share-one", version: 1 };
  let currentBackground = await syntheticPng(page, "seagreen", "midnightblue");
  const replacementBackground = await syntheticPng(
    page,
    "royalblue",
    "goldenrod",
  );
  let patchCount = 0,
    backgroundCount = 0,
    backgroundGets = 0,
    latestPatchFractionDigits = 0,
    latestPatchX = 0;
  let latestPatchBody: unknown = null,
    latestPatchHasBackground: boolean | null = null;
  const latestPatchBySlot = new Map<
    string,
    { readonly x: number; readonly y: number }
  >();
  let rejectNextBackgroundRead = false,
    rejectNextPatch = false,
    rejectNextBackground = true;
  let holdNextPatch = false,
    holdNextSuccessfulPatch = false;
  let releaseFirstPatch = () => undefined,
    releaseRejectedPatch = () => undefined,
    releaseSuccessfulPatch = () => undefined;
  const firstPatchGate = new Promise<void>((resolve) => {
      releaseFirstPatch = resolve;
    }),
    rejectedPatchGate = new Promise<void>((resolve) => {
      releaseRejectedPatch = resolve;
    });
  let successfulPatchGate = Promise.resolve();
  const holdSuccessfulPatch = () => {
    holdNextSuccessfulPatch = true;
    successfulPatchGate = new Promise<void>((resolve) => {
      releaseSuccessfulPatch = resolve;
    });
  };
  const apiUrl = `${origin}/api/v1/admin/boards/${boardId}`;
  const expectedConsoleErrors = new Set([
    `Failed to load resource: the server responded with a status of 404 (Not Found) @ ${apiUrl}/events`,
    `Failed to load resource: the server responded with a status of 404 (Not Found) @ ${apiUrl}/background`,
    `Failed to load resource: the server responded with a status of 409 (Conflict) @ ${apiUrl}/slots/${slotOne}`,
    `api_request_failed {code: CONFLICT, method: PATCH, requestId: null, route: /api/v1/admin/boards/:id/slots/:id, status: 409} @ ${origin}/@vite/client`,
    `Failed to load resource: the server responded with a status of 400 (Bad Request) @ ${apiUrl}/background`,
    `api_request_failed {code: UNKNOWN, method: POST, requestId: null, route: /api/v1/admin/boards/:id/background, status: 400} @ ${origin}/@vite/client`,
  ]);
  const browserErrors: string[] = [];
  const networkSequence: {
    readonly method: string;
    readonly pathname: string;
    readonly status: number;
  }[] = [];
  page.on("pageerror", (error) => browserErrors.push(error.message));
  page.on("response", (response) => {
    const pathname = new URL(response.url()).pathname;
    if (pathname.startsWith("/api/v1/"))
      networkSequence.push({
        method: response.request().method(),
        pathname,
        status: response.status(),
      });
  });
  page.on("console", (message) => {
    const error = `${message.text()} @ ${message.location().url}`;
    if (message.type() === "error" && !expectedConsoleErrors.has(error))
      browserErrors.push(error);
  });
  await page.route("**/api/v1/**", async (route: Route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    if (pathname === "/api/v1/auth/session")
      return route.fulfill({
        json: {
          authenticated: true,
          expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
        },
      });
    if (
      pathname === `/api/v1/admin/boards/${boardId}` &&
      request.method() === "GET"
    )
      return route.fulfill({ json: board });
    if (pathname === `/api/v1/admin/boards/${boardId}/roster`) {
      return route.fulfill({ json: roster });
    }
    if (
      pathname === `/api/v1/admin/boards/${boardId}/share` &&
      request.method() === "GET"
    )
      return route.fulfill({ json: share });
    if (pathname.endsWith("/share/reissue")) {
      share = { shareToken: "safe-share-two", version: 2 };
      return route.fulfill({ json: share });
    }
    if (pathname.endsWith("/background")) {
      if (request.method() === "GET") {
        backgroundGets += 1;
        if (rejectNextBackgroundRead) {
          rejectNextBackgroundRead = false;
          return route.fulfill({
            status: 404,
            json: {
              code: "OBJECT_KEY_MISSING",
              objectKey: "private/owner/board.png",
            },
          });
        }
        return route.fulfill({
          body: currentBackground,
          contentType: "image/png",
        });
      }
      backgroundCount += 1;
      if (rejectNextBackground) {
        rejectNextBackground = false;
        return route.fulfill({
          status: 400,
          json: { code: "BACKGROUND_INVALID" },
        });
      }
      if (
        request.postData()?.includes('name="adoptSourceRatio"\r\n\r\ntrue') ===
        true
      )
        board = { ...board, canvasHeight: 1200 };
      currentBackground = replacementBackground;
      return route.fulfill({
        json: {
          displayHeight: board.canvasHeight,
          displayWidth: board.canvasWidth,
          id: "41111111-1111-4111-8111-111111111111",
          mimeType: "image/png",
        },
      });
    }
    if (/\/slots\//u.test(pathname) && request.method() === "PATCH") {
      patchCount += 1;
      const body = request.postDataJSON(),
        slotId = pathname.split("/").at(-1);
      if (slotId === undefined) throw new Error("Missing slot id");
      latestPatchBody = body;
      latestPatchHasBackground = Object.hasOwn(body, "background");
      expect(latestPatchHasBackground).toBe(false);
      latestPatchFractionDigits = Math.max(
        ...[body.height, body.width, body.x, body.y].map(fractionDigits),
      );
      latestPatchX = body.x;
      latestPatchBySlot.set(slotId, { x: body.x, y: body.y });
      if (patchCount === 1) await firstPatchGate;
      if (holdNextPatch) {
        holdNextPatch = false;
        await rejectedPatchGate;
      }
      if (rejectNextPatch) {
        rejectNextPatch = false;
        return route.fulfill({ status: 409, json: { code: "SLOT_OVERLAP" } });
      }
      if (holdNextSuccessfulPatch) {
        holdNextSuccessfulPatch = false;
        await successfulPatchGate;
      }
      roster = roster.map((entry) =>
        entry.slot.id === slotId
          ? {
              ...entry,
              slot: {
                ...entry.slot,
                height: body.height,
                placementStatus: "PLACED",
                revision: entry.slot.revision + 1,
                width: body.width,
                x: body.x,
                y: body.y,
              },
            }
          : entry,
      );
      const entry = roster.find((candidate) => candidate.slot.id === slotId);
      if (entry === undefined)
        return route.fulfill({ status: 404, json: { code: "NOT_FOUND" } });
      return route.fulfill({
        json: {
          boardId,
          bounds: {
            height: entry.slot.height,
            width: entry.slot.width,
            x: entry.slot.x,
            y: entry.slot.y,
          },
          id: entry.slot.id,
          revision: entry.slot.revision,
          rosterEntryId: entry.id,
          signaturePresent: false,
          submitted: false,
        },
      });
    }
    if (/\/slots\//u.test(pathname) && request.method() === "DELETE")
      return route.fulfill({ status: 204 });
    if (/\/(open|close|reopen)$/u.test(pathname)) {
      board = {
        ...board,
        status: pathname.endsWith("/close") ? "마감/보관" : "서명 진행",
      };
      return route.fulfill({ json: board });
    }
    if (
      pathname === `/api/v1/admin/boards/${boardId}` &&
      request.method() === "PATCH"
    ) {
      const body = request.postDataJSON();
      board = {
        ...board,
        title: typeof body.title === "string" ? body.title : board.title,
      };
      return route.fulfill({ json: board });
    }
    return route.fulfill({ status: 404, json: { code: "NOT_FOUND" } });
  });

  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto(`/boards/${boardId}/edit`);
  const canvas = page.getByRole("application", { name: "서명 보드 캔버스" });
  const backgroundPanel = page.getByRole("heading", { name: "배경" });
  const sharePanel = page.getByRole("heading", { name: "공유" });
  const toolbar = page.getByLabel("자동 저장 상태");
  await expect(canvas.locator("img.editor-canvas-background")).toBeVisible();
  await captureState(page, "default-current-background-load", {
    anchor: canvas,
  });
  await page.reload();
  await expect.poll(() => backgroundGets).toBeGreaterThanOrEqual(2);
  await expect(canvas.locator("img.editor-canvas-background")).toBeVisible();
  await captureState(page, "current-background-reload", {
    anchor: canvas,
    scrollOffset: 1,
  });
  rejectNextBackgroundRead = true;
  await page.reload();
  const backgroundFailure = page
    .getByRole("alert")
    .filter({ hasText: "배경을 불러오지 못했습니다" });
  await expect(backgroundFailure).toBeVisible();
  await expect(backgroundFailure).not.toContainText("OBJECT_KEY_MISSING");
  await expect(backgroundFailure).not.toContainText("private/owner/board.png");
  await expect(page.getByLabel("PNG 또는 JPEG")).toBeDisabled();
  await captureState(page, "background-load-failure", {
    anchor: backgroundFailure,
  });
  await page.getByRole("button", { name: "배경 다시 불러오기" }).click();
  await expect(canvas.locator("img.editor-canvas-background")).toBeVisible();
  await captureState(page, "background-load-retry-recovered", {
    anchor: canvas,
    scrollOffset: 2,
  });
  expect(
    await page
      .locator(".board-editor button")
      .evaluateAll(
        (buttons) =>
          buttons.filter(
            (button) =>
              !button.classList.contains("board-button") &&
              button.closest("dialog") === null,
          ).length,
      ),
  ).toBe(0);
  const keyboardPlacement = page.getByRole("button", { name: "한별 배치" });
  const nextKeyboardPlacement = page.getByRole("button", { name: "누리 배치" });
  await keyboardPlacement.focus();
  await page.keyboard.press("Tab");
  await expect(nextKeyboardPlacement).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(keyboardPlacement).toBeFocused();
  const focusMetrics = await keyboardPlacement.evaluate((element) => {
    const style = getComputedStyle(element),
      root = getComputedStyle(document.documentElement),
      bounds = element.getBoundingClientRect(),
      width = Number.parseFloat(style.outlineWidth),
      offset = Number.parseFloat(style.outlineOffset),
      focusWidthValue = root.getPropertyValue("--focus-width").trim(),
      focusWidthPixels = focusWidthValue.endsWith("rem")
        ? Number.parseFloat(focusWidthValue) * Number.parseFloat(root.fontSize)
        : Number.parseFloat(focusWidthValue);
    return {
      outlineStyle: style.outlineStyle,
      outlineWidth: width,
      tokenWidth: focusWidthPixels,
      visibleBounds:
        bounds.left - width - offset >= 0 &&
        bounds.right + width + offset <= innerWidth &&
        bounds.top - width - offset >= 0 &&
        bounds.bottom + width + offset <= innerHeight,
    };
  });
  expect(focusMetrics).toMatchObject({
    outlineStyle: "solid",
    outlineWidth: focusMetrics.tokenWidth,
    visibleBounds: true,
  });
  await captureState(page, "keyboard-placement-focus", {
    anchor: keyboardPlacement,
  });
  await page.getByRole("button", { name: "한별 배치" }).click();
  await expect.poll(() => patchCount).toBe(1);
  releaseFirstPatch();
  await expect(page.getByRole("button", { name: "한별 이동" })).toBeVisible();
  const placedSlot = page.getByTestId(`slot-${slotOne}`);
  await expect(
    page.getByRole("button", { name: "한별 칸 배경 변경" }),
  ).toHaveCount(0);
  await expect(placedSlot).toHaveCSS("background-color", "rgba(0, 0, 0, 0)");
  await placedSlot.screenshot({
    path: path.join(evidenceDir, "task-2-slot-transparent.png"),
  });
  await writeFile(
    path.join(evidenceDir, "task-2-browser-manual-pass.json"),
    `${JSON.stringify(
      {
        backgroundControlCount: await page
          .getByRole("button", { name: "한별 칸 배경 변경" })
          .count(),
        geometry: await placedSlot.evaluate((element) => ({
          height: element.style.height,
          left: element.style.left,
          top: element.style.top,
          width: element.style.width,
        })),
        patchCount,
        patchHasBackground: latestPatchHasBackground,
        slotBackgroundColor: await placedSlot.evaluate(
          (element) => getComputedStyle(element).backgroundColor,
        ),
      },
      null,
      2,
    )}\n`,
  );
  if (process.env.TASK2_OVERLAP_QA === "1") {
    await nextKeyboardPlacement.click();
    await expect.poll(() => patchCount).toBe(2);
    const secondPlacedSlot = page.getByTestId(`slot-${slotTwo}`);
    await expect(secondPlacedSlot).toBeVisible();
    await page.getByRole("button", { name: "누리 이동" }).press("ArrowLeft");
    await expect.poll(() => patchCount).toBe(3);
    await expect(toolbar).toHaveText("저장됨");
    await expect(placedSlot).toHaveClass(/slot-overlay--collision/u);
    await expect(secondPlacedSlot).toHaveClass(/slot-overlay--collision/u);
    await expect(placedSlot).toHaveCSS("background-color", "rgba(0, 0, 0, 0)");
    await expect(secondPlacedSlot).toHaveCSS(
      "background-color",
      "rgba(0, 0, 0, 0)",
    );
    await placedSlot.screenshot({
      path: path.join(evidenceDir, "task-2-overlap-transparent.png"),
    });
    await writeFile(
      path.join(evidenceDir, "task-2-browser-overlap-pass.json"),
      `${JSON.stringify(
        {
          backgroundControlCount: await page
            .getByRole("button", { name: "한별 칸 배경 변경" })
            .count(),
          latestPatchBody,
          patchCount,
          patchHasBackground: latestPatchHasBackground,
          slots: await Promise.all(
            [placedSlot, secondPlacedSlot].map(async (slot) => ({
              backgroundColor: await slot.evaluate(
                (element) => getComputedStyle(element).backgroundColor,
              ),
              className: await slot.getAttribute("class"),
              geometry: await slot.evaluate((element) => ({
                height: element.style.height,
                left: element.style.left,
                top: element.style.top,
                width: element.style.width,
              })),
            })),
          ),
        },
        null,
        2,
      )}\n`,
    );
  }
  if (process.env.TASK2_SCOPED_QA === "1") return;
  await captureState(page, "keyboard-placed", { anchor: canvas });
  const canvasBox = await canvas.boundingBox();
  if (canvasBox === null) throw new Error("Canvas bounds unavailable");
  await nextKeyboardPlacement.dragTo(canvas, {
    targetPosition: { x: canvasBox.width * 0.24, y: 0 },
  });
  await expect.poll(() => patchCount).toBe(2);
  await expect(page.getByLabel("자동 저장 상태")).toHaveText("저장됨");
  await captureState(page, "drag-touching-edge", { anchor: canvas });
  await page
    .getByRole("button", { name: "누리 크기 조절" })
    .press("Shift+ArrowRight");
  await expect.poll(() => patchCount).toBe(3);
  await captureState(page, "resize-visible-handle", { anchor: canvas });

  const moveButton = page.getByRole("button", { name: "한별 이동" }),
    movingSlot = page.getByTestId(`slot-${slotOne}`);
  const secondMoveButton = page.getByRole("button", { name: "누리 이동" }),
    secondMovingSlot = page.getByTestId(`slot-${slotTwo}`);
  await moveButton.press("ArrowRight");
  await moveButton.press("ArrowRight");
  await secondMoveButton.press("ArrowDown");
  const firstPreviewX =
      Number.parseFloat(
        await movingSlot.evaluate((element) => element.style.left),
      ) / 100,
    secondPreviewY =
      Number.parseFloat(
        await secondMovingSlot.evaluate((element) => element.style.top),
      ) / 100;
  expect(patchCount).toBe(3);
  await expect.poll(() => patchCount).toBe(5);
  expect(latestPatchBySlot.get(slotOne)?.x).toBeCloseTo(firstPreviewX);
  expect(latestPatchBySlot.get(slotTwo)?.y).toBeCloseTo(secondPreviewY);

  await page.getByRole("button", { name: "링크 복사" }).click();
  const copied =
    browserName === "chromium"
      ? await page.evaluate(() => navigator.clipboard.readText())
      : ((await page
          .locator("html")
          .getAttribute("data-task23-clipboard-write")) ?? "");
  expect(copied).toBe(`${origin}/sign/safe-share-one`);
  const bits = encodeQR(copied, "raw", { ecc: "medium" });
  expect(
    decodeQR(
      new Bitmap({ height: bits.length, width: bits[0]?.length ?? 0 }, bits)
        .scale(4)
        .toImage(),
    ),
  ).toBe(copied);
  await page.getByRole("button", { name: "링크 재발급" }).click();
  await expect(page.getByText(/기존 링크는 즉시 무효화/u)).toBeVisible();
  await captureState(page, "share-reissue-confirmation", { dialog: true });
  await page.getByRole("button", { name: "취소" }).click();
  await page.getByRole("button", { name: "링크 재발급" }).click();
  await page.getByRole("button", { exact: true, name: "재발급" }).click();
  await expect(page.locator("svg[data-share-url]")).toHaveAttribute(
    "data-share-url",
    /safe-share-two/u,
  );
  await captureState(page, "share-reissued", { anchor: sharePanel });
  await page.evaluate(() =>
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: () => Promise.reject(new DOMException("denied")) },
    }),
  );
  await page.getByRole("button", { name: "링크 복사" }).click();
  const copyFailure = page.getByText(/링크를 복사하지 못했습니다/u);
  await expect(copyFailure).toBeVisible();
  await captureState(page, "share-copy-failure", { anchor: copyFailure });

  rejectNextPatch = true;
  holdNextPatch = true;
  holdSuccessfulPatch();
  await moveButton.press("ArrowRight");
  await moveButton.press("ArrowRight");
  await moveButton.press("ArrowLeft");
  const coalescedPreviewX =
    Number.parseFloat(
      await movingSlot.evaluate((element) => element.style.left),
    ) / 100;
  expect(patchCount).toBe(5);
  await expect.poll(() => patchCount).toBe(6);
  expect(latestPatchX).toBeCloseTo(coalescedPreviewX);
  await moveButton.press("ArrowLeft");
  await moveButton.press("ArrowRight");
  await moveButton.press("ArrowRight");
  await new Promise((resolve) => setTimeout(resolve, 100));
  expect(patchCount).toBe(6);
  releaseRejectedPatch();
  await expect.poll(() => patchCount).toBe(7);
  await expect(page.getByText(/서버 배치와 충돌/u)).toBeVisible();
  await expect(page.getByLabel("자동 저장 상태")).toHaveText("저장 실패");
  await captureState(page, "authoritative-409-recovery", { anchor: canvas });
  releaseSuccessfulPatch();
  await expect(page.getByLabel("자동 저장 상태")).toHaveText("저장 실패");
  holdSuccessfulPatch();
  await moveButton.press("ArrowRight");
  await expect.poll(() => patchCount).toBe(8);
  releaseSuccessfulPatch();
  await expect(page.getByLabel("자동 저장 상태")).toHaveText("저장됨");

  const pointerMoveBounds = await moveButton.boundingBox();
  if (pointerMoveBounds === null)
    throw new Error("Move control bounds unavailable");
  const pointerMovePatchCount = patchCount;
  await page.mouse.move(
    pointerMoveBounds.x + pointerMoveBounds.width / 2,
    pointerMoveBounds.y + pointerMoveBounds.height / 2,
  );
  await page.mouse.down();
  await page.mouse.move(
    pointerMoveBounds.x + pointerMoveBounds.width / 2 + 17,
    pointerMoveBounds.y + pointerMoveBounds.height / 2 + 13,
  );
  await page.mouse.up();
  await expect.poll(() => patchCount).toBe(pointerMovePatchCount + 1);
  expect(latestPatchFractionDigits).toBeLessThanOrEqual(8);

  const upload = page.getByLabel("PNG 또는 JPEG");
  const currentBackgroundSrc = await canvas
    .locator("img.editor-canvas-background")
    .getAttribute("src");
  await upload.setInputFiles({
    buffer: replacementBackground,
    mimeType: "image/png",
    name: "synthetic.png",
  });
  await page.getByRole("button", { name: "기존 비율로 교체" }).click();
  await expect.poll(() => backgroundCount).toBe(1);
  await writeFile(
    path.join(evidenceDir, "upload-rejection-observed.json"),
    `${JSON.stringify(
      {
        alertCount: await page
          .getByText(/선택한 파일을 확인해 주세요/u)
          .count(),
        backgroundCount,
        backgroundSrc: await canvas
          .locator("img.editor-canvas-background")
          .getAttribute("src"),
        backgroundUnchanged:
          (await canvas
            .locator("img.editor-canvas-background")
            .getAttribute("src")) === currentBackgroundSrc,
        fileCount: await upload.evaluate(
          (element: HTMLInputElement) => element.files?.length ?? 0,
        ),
        fileName: await upload.evaluate(
          (element: HTMLInputElement) => element.files?.item(0)?.name ?? null,
        ),
        inkColor: await page
          .getByRole("radio", { checked: true })
          .getAttribute("value"),
        messages: await page
          .locator('[role="alert"], [role="status"]')
          .allTextContents(),
        networkSequence,
      },
      null,
      2,
    )}\n`,
  );
  await page.screenshot({
    fullPage: true,
    path: path.join(evidenceDir, "upload-rejection-observed.png"),
  });
  await expect(page.getByText(/선택한 파일을 확인해 주세요/u)).toBeVisible();
  await expect(upload).toHaveJSProperty("files.length", 1);
  await expect(canvas.locator("img.editor-canvas-background")).toHaveAttribute(
    "src",
    currentBackgroundSrc ?? "",
  );
  await captureState(page, "upload-reject-retains-file", {
    anchor: backgroundPanel,
  });
  await captureState(page, "upload-reject-retains-current-background", {
    anchor: canvas,
  });
  await page.getByRole("button", { name: "이미지 비율 적용" }).click();
  await captureState(page, "ratio-adoption-cancel-confirmation", {
    dialog: true,
  });
  await page.getByRole("button", { name: "취소" }).click();
  expect(backgroundCount).toBe(1);
  await page.getByRole("button", { name: "이미지 비율 적용" }).click();
  await page.getByRole("button", { exact: true, name: "비율 적용" }).click();
  await expect.poll(() => backgroundCount).toBe(2);
  await expect(canvas.locator("img.editor-canvas-background")).toBeVisible();
  await expect(upload).toHaveJSProperty("files.length", 0);
  await writeFile(
    path.join(evidenceDir, "upload-success-observed.json"),
    `${JSON.stringify(
      {
        alertCount: await page
          .getByText(/선택한 파일을 확인해 주세요/u)
          .count(),
        backgroundCount,
        backgroundSrcChanged:
          (await canvas
            .locator("img.editor-canvas-background")
            .getAttribute("src")) !== currentBackgroundSrc,
        fileCount: await upload.evaluate(
          (element: HTMLInputElement) => element.files?.length ?? 0,
        ),
        inkColor: await page
          .getByRole("radio", { checked: true })
          .getAttribute("value"),
        networkSequence,
      },
      null,
      2,
    )}\n`,
  );
  await captureState(page, "ratio-adoption-confirmed-background", {
    anchor: canvas,
  });

  await page.getByRole("button", { name: "서명 시작" }).click();
  await captureState(page, "lifecycle-open", { anchor: toolbar });
  await page.getByRole("button", { name: "마감" }).click();
  await captureState(page, "lifecycle-closed", { anchor: toolbar });
  await page.getByRole("button", { name: "다시 열기" }).click();
  await expect(page.getByText("서명 진행").first()).toBeVisible();
  await page.getByLabel("보드 제목").focus();
  await captureState(page, "lifecycle-reopened", { anchor: toolbar });
  const overflow = await page
    .locator("body *")
    .evaluateAll((elements) =>
      elements
        .filter(
          (element) =>
            element.getBoundingClientRect().right > window.innerWidth + 1,
        )
        .map((element) => `${element.tagName}.${element.className}`),
    );
  expect(overflow).toEqual([]);
  expect(browserErrors).toEqual([]);
});
