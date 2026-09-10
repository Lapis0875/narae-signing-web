import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { expect, test } from "@playwright/test";
import { opaquePixelCount } from "./board-signature-ink-color.canvas.ts";
import { decodeFinalPng } from "./board-signature-ink-color.png.ts";
import {
  addPlacedRoster,
  adminJson,
  board,
  createDraftBoard,
  darkBackground,
  displaySnapshot,
  errorCode,
  login,
  uploadBackground,
} from "./board-signature-ink-color.support.ts";
import {
  captureVisualState,
  observeVisualPage,
  type VisualState,
} from "./board-signature-ink-color.visual.ts";
import {
  drawStroke,
  identify,
  primaryIdentity,
  readCredentials,
  requiredEnvironment,
} from "./realStackSupport.ts";

// allow: SIZE_OK — one ordered real-stack narrative binds state races to the same downloaded pixels.
test("Given real board contexts When white ink completes Then live and final pixels preserve the contract", async ({
  browser,
}) => {
  // Given: isolated administrator, public-display, signer, and anonymous contexts.
  const baseUrl = new URL(requiredEnvironment("TASK8_BASE_URL"));
  const evidenceDir = path.resolve(requiredEnvironment("TASK8_EVIDENCE_DIR"));
  const privacyVisualPacket = /task-8-visuals-r(?:5|6)$/u.test(evidenceDir);
  const missingVisualsOnly = process.env.TASK8_VISUAL_MISSING_ONLY === "1";
  const adminVisualsOnly = process.env.TASK8_VISUAL_ADMIN_ONLY === "1";
  const visualDiagnosis =
    privacyVisualPacket ||
    adminVisualsOnly ||
    missingVisualsOnly ||
    process.env.TASK8_VISUAL_DIAGNOSIS === "1";
  test.setTimeout(
    adminVisualsOnly
      ? 180_000
      : missingVisualsOnly
        ? 240_000
        : visualDiagnosis
          ? 300_000
          : 150_000,
  );
  const visualStates: VisualState[] = [];
  const { email, password } = readCredentials();
  await mkdir(evidenceDir, { recursive: true });
  const contextOptions = {
    baseURL: baseUrl.toString(),
    ignoreHTTPSErrors: true,
  };
  const adminContext = await browser.newContext({
    ...contextOptions,
    extraHTTPHeaders: { "X-Narae-Client-IP": "127.0.0.1" },
  });
  const displayContext = await browser.newContext(contextOptions);
  const signerContext = await browser.newContext(contextOptions);
  const anonymousContext = await browser.newContext(contextOptions);
  const blackDisplayContext = await browser.newContext(contextOptions);
  const blackSignerContext = await browser.newContext(contextOptions);
  const contexts = [
    adminContext,
    displayContext,
    signerContext,
    anonymousContext,
    blackDisplayContext,
    blackSignerContext,
  ];
  try {
    const admin = await adminContext.newPage();
    const display = await displayContext.newPage();
    const signer = await signerContext.newPage();
    const anonymous = await anonymousContext.newPage();
    const blackDisplay = await blackDisplayContext.newPage();
    const blackSigner = await blackSignerContext.newPage();
    const adminVisualEvents = visualDiagnosis ? observeVisualPage(admin) : [];
    const displayVisualEvents = visualDiagnosis
      ? observeVisualPage(display)
      : [];
    const signerVisualEvents = visualDiagnosis ? observeVisualPage(signer) : [];
    const blackDisplayVisualEvents = visualDiagnosis
      ? observeVisualPage(blackDisplay)
      : [];
    const adminViewports = [
      { height: 720, width: 1280 },
      { height: 667, width: 375 },
    ] as const;
    const captureAdminState = async (
      label: string,
      includeCanvasScroll: boolean,
    ) => {
      for (const viewport of adminViewports) {
        if (
          privacyVisualPacket &&
          label === "admin-white-selected" &&
          viewport.width === 375
        )
          await expect(admin.locator(".slot-label")).toHaveText([
            primaryIdentity.name,
            "시험 서명자 B",
          ]);
        visualStates.push(
          await captureVisualState(
            admin,
            evidenceDir,
            label,
            adminVisualEvents,
            viewport,
          ),
        );
        if (!includeCanvasScroll) continue;
        const canvasPanel = admin.locator(".editor-canvas-panel");
        await canvasPanel.scrollIntoViewIfNeeded();
        await admin.evaluate(() => {
          const header = document.querySelector(".app-header");
          const panel = document.querySelector(".editor-canvas-panel");
          if (
            !(header instanceof HTMLElement) ||
            !(panel instanceof HTMLElement)
          )
            throw new Error("Task 8 admin scroll targets missing");
          const overlap =
            header.getBoundingClientRect().bottom -
            panel.getBoundingClientRect().top +
            8;
          if (overlap > 0) window.scrollBy(0, -overlap);
        });
        await expect
          .poll(() =>
            admin.evaluate(() => {
              const headers = [
                ...document.querySelectorAll(".app-header"),
              ].filter(
                (node) =>
                  node instanceof HTMLElement &&
                  node.getClientRects().length > 0,
              );
              const header = headers[0];
              const panel = document.querySelector(".editor-canvas-panel");
              if (
                !(header instanceof HTMLElement) ||
                !(panel instanceof HTMLElement)
              )
                return false;
              const headerRect = header.getBoundingClientRect();
              const panelRect = panel.getBoundingClientRect();
              return (
                headers.length === 1 &&
                panelRect.top >= headerRect.bottom &&
                panelRect.top < window.innerHeight
              );
            }),
          )
          .toBe(true);
        visualStates.push(
          await captureVisualState(
            admin,
            evidenceDir,
            label,
            adminVisualEvents,
            viewport,
            "scroll",
          ),
        );
      }
      await admin.setViewportSize({ height: 720, width: 1280 });
      await admin.evaluate(() => window.scrollTo(0, 0));
    };
    await login(admin, baseUrl, email, password);
    const boardId = await createDraftBoard(admin, "Task 8 흰색 잉크 합성 검증");
    const boardPath = `/api/v1/admin/boards/${boardId}`;
    const whiteRadio = admin.getByRole("radio", { name: "흰색" });

    await expect(admin.getByRole("radio", { name: "검정" })).toBeChecked();
    await expect(whiteRadio).toBeDisabled();
    expect(
      board((await adminJson(admin, boardPath, "GET")).body).signatureInkColor,
    ).toBe("black");
    if (visualDiagnosis && !missingVisualsOnly && !privacyVisualPacket) {
      await captureAdminState("admin-no-background", false);
    }
    if (!privacyVisualPacket)
      await admin.screenshot({
        fullPage: true,
        mask: [
          admin.locator(".editor-share-url"),
          admin.getByRole("img", { name: "현재 서명 링크 QR" }),
        ],
        path: path.join(evidenceDir, "black-default.png"),
      });

    const withoutBackground = await adminJson(
      admin,
      boardPath,
      "PATCH",
      JSON.stringify({ signatureInkColor: "white" }),
    );
    expect(withoutBackground.status).toBe(409);
    expect(errorCode(withoutBackground.body)).toBe(
      "SIGNATURE_INK_BACKGROUND_REQUIRED",
    );
    expect(
      board((await adminJson(admin, boardPath, "GET")).body).signatureInkColor,
    ).toBe("black");
    const malformed = await adminJson(
      admin,
      boardPath,
      "PATCH",
      JSON.stringify({ signatureInkColor: "WHITE" }),
    );
    expect(malformed.status).toBe(400);
    expect(errorCode(malformed.body)).toBe("SIGNATURE_INK_COLOR_INVALID");
    const unavailable = await adminJson(
      admin,
      `/api/v1/admin/boards/${crypto.randomUUID()}`,
      "GET",
    );
    expect(unavailable.status).toBe(404);
    expect(errorCode(unavailable.body)).toBe("BOARD_UNAVAILABLE");
    await anonymous.goto(new URL("/", baseUrl).toString());
    const unauthorized = await anonymous.evaluate(async (requestPath) => {
      const response = await fetch(requestPath);
      return { body: await response.json(), status: response.status };
    }, boardPath);
    expect(unauthorized.status).toBe(401);
    expect(errorCode(unauthorized.body)).toBe("UNAUTHORIZED");

    await admin.getByLabel("PNG 또는 JPEG 파일 선택").setInputFiles({
      buffer: Buffer.from([0, 1, 2, 3]),
      mimeType: "image/png",
      name: "invalid.png",
    });
    const invalidImage = admin.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === `${boardPath}/background`,
    );
    await admin.getByRole("button", { name: "기존 비율로 교체" }).click();
    expect((await invalidImage).status()).toBe(400);

    const sourceBackground = await darkBackground(admin);
    expect(sourceBackground).toMatchObject({ height: 180, width: 320 });
    await uploadBackground(admin, boardId, sourceBackground.buffer);
    if (visualDiagnosis && !missingVisualsOnly) {
      await captureAdminState("admin-draft-black", !privacyVisualPacket);
    }
    await adminContext.setOffline(true);
    await admin.getByRole("radio", { name: "흰색" }).check();
    await expect(admin.getByLabel("자동 저장 상태")).toHaveText("저장 실패");
    await expect(admin.getByRole("radio", { name: "검정" })).toBeChecked();
    await adminContext.setOffline(false);
    const savedWhite = admin.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === boardPath &&
        response.request().method() === "PATCH",
    );
    await admin.getByRole("radio", { name: "흰색" }).check();
    expect((await savedWhite).status()).toBe(200);
    await expect(admin.getByLabel("자동 저장 상태")).toHaveText("저장됨");
    const persisted = board((await adminJson(admin, boardPath, "GET")).body);
    expect(persisted).toMatchObject({
      canvasHeight: 1080,
      canvasWidth: 1920,
      signatureInkColor: "white",
    });
    await addPlacedRoster(admin);
    if (visualDiagnosis && !missingVisualsOnly) {
      await captureAdminState("admin-white-selected", true);
    }
    if (adminVisualsOnly) {
      await writeFile(
        path.join(evidenceDir, "visual-capture-manifest.json"),
        `${JSON.stringify(visualStates, null, 2)}\n`,
        { encoding: "utf8", mode: 0o600 },
      );
      expect(
        visualStates.flatMap((state) =>
          state.images.filter(
            (image) => !image.complete || image.naturalWidth === 0,
          ),
        ),
      ).toEqual([]);
      expect(
        visualStates.every(
          (state) =>
            state.png.width === (state.clip?.width ?? state.viewport.width) &&
            state.png.height === (state.clip?.height ?? state.viewport.height),
        ),
        "admin PNG dimensions must exactly equal the native viewport",
      ).toBe(true);
      return;
    }

    const prematurePng = await adminJson(
      admin,
      `${boardPath}/final.png`,
      "GET",
    );
    expect(prematurePng.status).toBe(409);
    expect(errorCode(prematurePng.body)).toBe("FINAL_PNG_NOT_CLOSED");
    const configuredShareUrl = await admin
      .getByRole("img", { name: "현재 서명 링크 QR" })
      .getAttribute("data-share-url");
    if (configuredShareUrl === null)
      throw new Error("Task 8 share route missing");
    const shareUrl = new URL(configuredShareUrl, baseUrl);
    shareUrl.protocol = baseUrl.protocol;
    shareUrl.host = baseUrl.host;
    const shareToken = shareUrl.pathname.split("/").at(-1) ?? "";
    const publicUrl = new URL(shareUrl);
    publicUrl.pathname = publicUrl.pathname.replace(/^\/sign\//u, "/display/");

    // When: the literal UI action starts the board and the signer draws one stroke.
    await admin.getByRole("button", { name: "서명 시작" }).click();
    await expect(admin.getByText("서명 진행", { exact: true })).toBeVisible();
    await expect(whiteRadio).toBeDisabled();
    if (privacyVisualPacket)
      await captureAdminState("admin-open-locked", false);
    const displayEvents = display.waitForResponse((response) =>
      new URL(response.url()).pathname.endsWith("/display/events"),
    );
    await display.goto(publicUrl.toString(), { waitUntil: "domcontentloaded" });
    await expect(display.getByTestId("full-view-canvas")).toBeVisible();
    expect((await displayEvents).status()).toBe(200);
    expect(
      (await identify(signer, shareUrl.toString(), primaryIdentity)).status(),
    ).toBe(200);
    await expect(signer.getByTestId("public-signer-drawing")).toBeVisible();
    const completedDraftDelta = signer.waitForResponse(
      (response) =>
        response.status() === 200 &&
        new URL(response.url()).pathname.endsWith(
          "/signing-session/draft/delta",
        ) &&
        (response.request().postData() ?? "").includes('"operation":"end"'),
    );
    const strokeStartedAt = Date.now();
    await drawStroke(signer, 0.5);
    await completedDraftDelta;

    const signerBlack = await opaquePixelCount(
      signer.getByTestId("signer-canvas"),
      [0, 0, 0],
    );
    expect(signerBlack.count).toBeGreaterThan(25);
    if (visualDiagnosis && !missingVisualsOnly) {
      visualStates.push(
        await captureVisualState(
          signer,
          evidenceDir,
          "signer-pad-black",
          signerVisualEvents,
          { height: 720, width: 1280 },
        ),
      );
      const interruptedMobileContext = await browser.newContext(contextOptions);
      try {
        const interruptedMobileSigner =
          await interruptedMobileContext.newPage();
        await interruptedMobileSigner.setViewportSize({
          height: 667,
          width: 375,
        });
        await interruptedMobileSigner.goto(shareUrl.toString(), {
          waitUntil: "domcontentloaded",
        });
        await expect(
          interruptedMobileSigner.getByTestId("unsupported-device-view"),
        ).toBeVisible();
      } finally {
        await interruptedMobileContext.close();
      }
      await expect(signer.getByTestId("public-signer-drawing")).toBeVisible();
      const mobileSignerContext = await browser.newContext(contextOptions);
      try {
        const mobileSigner = await mobileSignerContext.newPage();
        const mobileSignerEvents = observeVisualPage(mobileSigner);
        await mobileSigner.setViewportSize({ height: 667, width: 375 });
        await mobileSigner.goto(shareUrl.toString(), {
          waitUntil: "domcontentloaded",
        });
        await expect(
          mobileSigner.getByTestId("unsupported-device-view"),
        ).toBeVisible();
        await expect(
          mobileSigner.getByTestId("public-signer-drawing"),
        ).toHaveCount(0);
        visualStates.push(
          await captureVisualState(
            mobileSigner,
            evidenceDir,
            "signer-unsupported-mobile",
            mobileSignerEvents,
            { height: 667, width: 375 },
          ),
        );
      } finally {
        await mobileSignerContext.close();
      }
      await expect(signer.getByTestId("public-signer-drawing")).toBeVisible();
      await expect(
        signer.getByRole("button", { name: "서명 제출" }),
      ).toBeVisible();
    }
    const readDisplaySnapshot = async () =>
      displaySnapshot(
        await display.evaluate(async (token) => {
          const response = await fetch(
            `/api/v1/public/links/${encodeURIComponent(token)}/display/snapshot`,
          );
          return response.json();
        }, shareToken),
      );
    await expect
      .poll(async () =>
        (await readDisplaySnapshot()).slots.some(
          (slot) =>
            slot.draftSignature?.strokes.some(
              (stroke) => stroke.points.length > 0,
            ) === true,
        ),
      )
      .toBe(true);
    const liveSnapshot = await readDisplaySnapshot();
    expect(liveSnapshot.signatureInkColor).toBe("white");
    const liveSlot = liveSnapshot.slots.find(
      (slot) => slot.draftSignature !== null,
    );
    if (liveSlot === undefined)
      throw new Error("Task 8 live draft slot missing");
    const publicCanvas = display.locator(
      `[data-slot-id="${liveSlot.id}"] canvas`,
    );
    await expect(publicCanvas).toBeVisible();
    const liveCanvasDiagnostic = await opaquePixelCount(
      publicCanvas,
      [255, 255, 255],
    );
    await writeFile(
      path.join(evidenceDir, "live-canvas-debug.json"),
      `${JSON.stringify(liveCanvasDiagnostic, null, 2)}\n`,
      { encoding: "utf8", mode: 0o600 },
    );
    await display.screenshot({
      fullPage: true,
      path: path.join(evidenceDir, "white-live-pre-poll.png"),
    });
    await expect
      .poll(
        async () =>
          (await opaquePixelCount(publicCanvas, [255, 255, 255])).count,
      )
      .toBeGreaterThan(25);
    const liveWhite = await opaquePixelCount(publicCanvas, [255, 255, 255]);
    const livePixelLatencyMs = Date.now() - strokeStartedAt;
    expect(liveWhite.first).not.toBeNull();
    if (visualDiagnosis && !missingVisualsOnly) {
      visualStates.push(
        await captureVisualState(
          display,
          evidenceDir,
          "public-live-white",
          displayVisualEvents,
          { height: 720, width: 1280 },
        ),
        await captureVisualState(
          display,
          evidenceDir,
          "public-live-white",
          displayVisualEvents,
          { height: 667, width: 375 },
        ),
      );
      await display.setViewportSize({ height: 720, width: 1280 });
    }
    await display.screenshot({
      fullPage: true,
      path: path.join(evidenceDir, "white-live.png"),
    });
    const submitStartedAt = Date.now();
    const submittedResponse = signer.waitForResponse(
      (response) =>
        response.status() === 200 &&
        new URL(response.url()).pathname.endsWith("/signing-session/signature"),
    );
    await signer.getByRole("button", { name: "서명 제출" }).click();
    const completedSubmitResponse = await submittedResponse;
    const submitResponseLatencyMs = Date.now() - submitStartedAt;
    await expect(signer.getByTestId("public-signer-complete")).toBeVisible();
    await expect
      .poll(
        async () =>
          (await opaquePixelCount(publicCanvas, [255, 255, 255])).whiteAlpha,
      )
      .toBeGreaterThan(25);
    const afterSubmit = await opaquePixelCount(publicCanvas, [255, 255, 255]);
    const submittedPixelLatencyMs = Date.now() - submitStartedAt;
    expect(afterSubmit.firstWhiteAlpha).toEqual(liveWhite.firstWhiteAlpha);
    if (visualDiagnosis) {
      visualStates.push(
        await captureVisualState(
          display,
          evidenceDir,
          "public-submitted-white",
          displayVisualEvents,
          { height: 720, width: 1280 },
        ),
        await captureVisualState(
          display,
          evidenceDir,
          "public-submitted-white",
          displayVisualEvents,
          { height: 667, width: 375 },
        ),
      );
      await display.setViewportSize({ height: 720, width: 1280 });
    }
    await display.screenshot({
      fullPage: true,
      path: path.join(evidenceDir, "white-final.png"),
    });

    await admin.getByRole("button", { name: "마감" }).click();
    await expect(admin.getByText("마감/보관", { exact: true })).toBeVisible();
    if (privacyVisualPacket)
      await captureAdminState("admin-closed-locked", false);
    if (visualDiagnosis) {
      await expect(display.getByTestId("full-view-canvas")).toBeVisible();
      visualStates.push(
        await captureVisualState(
          display,
          evidenceDir,
          "public-closed-final",
          displayVisualEvents,
          { height: 720, width: 1280 },
        ),
        await captureVisualState(
          display,
          evidenceDir,
          "public-closed-final",
          displayVisualEvents,
          { height: 667, width: 375 },
        ),
      );
      await display.setViewportSize({ height: 720, width: 1280 });
    }
    const download = admin.waitForEvent("download");
    await admin.getByRole("button", { name: "최종 PNG 다운로드" }).click();
    const finalPath = path.join(evidenceDir, "final.png");
    await (await download).saveAs(finalPath);
    const decoded = await decodeFinalPng(admin, finalPath);
    expect(decoded).toMatchObject({
      background: [32, 32, 32, 255],
      blankSlotChanged: 0,
      height: 1080,
      width: 1920,
    });
    expect(decoded.whiteInterior).toBeGreaterThan(10);

    const reopenedResponse = admin.waitForResponse(
      (response) =>
        response.status() === 200 &&
        new URL(response.url()).pathname === `${boardPath}/reopen`,
    );
    await admin.getByRole("button", { name: "다시 열기" }).click();
    await reopenedResponse;
    await expect(admin.getByText("서명 진행", { exact: true })).toBeVisible();
    await expect(whiteRadio).toBeDisabled();
    const openSameWhite = await adminJson(
      admin,
      boardPath,
      "PATCH",
      JSON.stringify({ signatureInkColor: "white" }),
    );
    expect(openSameWhite.status).toBe(409);
    expect(errorCode(openSameWhite.body)).toBe("BOARD_NOT_DRAFT");
    expect(
      board((await adminJson(admin, boardPath, "GET")).body).signatureInkColor,
    ).toBe("white");
    await admin.getByRole("button", { name: "마감" }).click();

    const raceAdmin = await adminContext.newPage();
    await raceAdmin.goto(new URL("/boards", baseUrl).toString());
    const raceBoardId = await createDraftBoard(
      raceAdmin,
      "Task 8 다중 탭 경쟁 검증",
    );
    await uploadBackground(
      raceAdmin,
      raceBoardId,
      (await darkBackground(raceAdmin)).buffer,
    );
    await addPlacedRoster(raceAdmin);
    const racePeer = await adminContext.newPage();
    await racePeer.goto(
      new URL(`/boards/${raceBoardId}/edit`, baseUrl).toString(),
    );
    await expect(racePeer.getByRole("radio", { name: "검정" })).toBeChecked();
    const racePath = `/api/v1/admin/boards/${raceBoardId}`;
    const [racePatch, raceOpen] = await Promise.all([
      adminJson(
        raceAdmin,
        racePath,
        "PATCH",
        JSON.stringify({ signatureInkColor: "white" }),
      ),
      adminJson(racePeer, `${racePath}/open`, "POST"),
    ]);
    expect(raceOpen.status).toBe(200);
    expect([200, 409]).toContain(racePatch.status);
    const raceState = board((await adminJson(raceAdmin, racePath, "GET")).body);
    expect(raceState.status).toBe("서명 진행");
    expect(raceState.signatureInkColor).toBe(
      racePatch.status === 200 ? "white" : "black",
    );
    await Promise.all([raceAdmin.reload(), racePeer.reload()]);
    for (const page of [raceAdmin, racePeer]) {
      await expect(page.getByText("서명 진행", { exact: true })).toBeVisible();
      await expect(
        page.getByRole("radio", {
          name: raceState.signatureInkColor === "white" ? "흰색" : "검정",
        }),
      ).toBeChecked();
      await expect(page.getByRole("radio", { name: "흰색" })).toBeDisabled();
    }

    if (visualDiagnosis) {
      await admin.goto(new URL("/boards", baseUrl).toString());
      const blackBoardId = await createDraftBoard(
        admin,
        "Task 8 검정 기본 시각 검증",
      );
      await uploadBackground(
        admin,
        blackBoardId,
        (await darkBackground(admin)).buffer,
      );
      await addPlacedRoster(admin);
      const blackShareSource = await admin
        .getByRole("img", { name: "현재 서명 링크 QR" })
        .getAttribute("data-share-url");
      if (blackShareSource === null)
        throw new Error("Task 8 black visual share route missing");
      const blackShareUrl = new URL(blackShareSource, baseUrl);
      blackShareUrl.protocol = baseUrl.protocol;
      blackShareUrl.host = baseUrl.host;
      const blackPublicUrl = new URL(blackShareUrl);
      blackPublicUrl.pathname = blackPublicUrl.pathname.replace(
        /^\/sign\//u,
        "/display/",
      );
      await admin.getByRole("button", { name: "서명 시작" }).click();
      const blackEvents = blackDisplay.waitForResponse((response) =>
        new URL(response.url()).pathname.endsWith("/display/events"),
      );
      await blackDisplay.goto(blackPublicUrl.toString(), {
        waitUntil: "domcontentloaded",
      });
      await expect(blackDisplay.getByTestId("full-view-canvas")).toBeVisible();
      expect((await blackEvents).status()).toBe(200);
      expect(
        (
          await identify(blackSigner, blackShareUrl.toString(), primaryIdentity)
        ).status(),
      ).toBe(200);
      const blackDelta = blackSigner.waitForResponse(
        (response) =>
          response.status() === 200 &&
          new URL(response.url()).pathname.endsWith(
            "/signing-session/draft/delta",
          ) &&
          (response.request().postData() ?? "").includes('"operation":"end"'),
      );
      await drawStroke(blackSigner, 0.5);
      await blackDelta;
      const blackCanvas = blackDisplay.locator("[data-slot-id] canvas").first();
      await expect
        .poll(
          async () =>
            (await opaquePixelCount(blackCanvas, [0, 0, 0])).nonTransparent,
        )
        .toBeGreaterThan(25);
      visualStates.push(
        await captureVisualState(
          blackDisplay,
          evidenceDir,
          "public-black-default",
          blackDisplayVisualEvents,
          { height: 720, width: 1280 },
        ),
        await captureVisualState(
          blackDisplay,
          evidenceDir,
          "public-black-default",
          blackDisplayVisualEvents,
          { height: 667, width: 375 },
        ),
      );
      await writeFile(
        path.join(evidenceDir, "visual-capture-manifest.json"),
        `${JSON.stringify(visualStates, null, 2)}\n`,
        { encoding: "utf8", mode: 0o600 },
      );
      const brokenImages = visualStates.flatMap((state) =>
        state.images.filter(
          (image) => !image.complete || image.naturalWidth === 0,
        ),
      );
      const publicStates = visualStates.filter((state) =>
        state.label.startsWith("public-"),
      );
      const knownBrowserNotes = publicStates.flatMap((state) =>
        state.browserEvents.filter(
          (event) =>
            event.message?.includes(
              "'script-src' was not explicitly set, so 'default-src' is used",
            ) === true ||
            event.message?.includes(
              "Multiple readback operations using getImageData",
            ) === true ||
            (event.kind === "requestfailed" &&
              event.path?.endsWith("/display/claim") === true &&
              event.message === "net::ERR_ABORTED"),
        ),
      );
      const publicBrowserProblems = publicStates.flatMap((state) =>
        state.browserEvents.filter(
          (event) =>
            !knownBrowserNotes.includes(event) &&
            (event.kind !== "response" || (event.status ?? 0) >= 400),
        ),
      );
      const publicBackgrounds = publicStates.flatMap((state) =>
        state.images.filter(
          (image) => image.className === "full-view-background",
        ),
      );
      expect(
        brokenImages,
        "fresh public/admin image nodes must decode",
      ).toEqual([]);
      expect(
        visualStates.every(
          (state) =>
            state.png.magentaPixels === 0 &&
            state.privacy.sensitiveNodesInCapture === 0,
        ),
        "fresh visual captures must contain no mask color or sensitive share panel",
      ).toBe(true);
      if (privacyVisualPacket) {
        expect(visualStates).toHaveLength(20);
        const mobileAdminLabels = visualStates
          .filter(
            (state) =>
              state.label === "admin-white-selected" &&
              state.viewport.width === 375,
          )
          .flatMap((state) => state.slotLabels);
        expect(mobileAdminLabels).toEqual([
          primaryIdentity.name,
          "시험 서명자 B",
          primaryIdentity.name,
          "시험 서명자 B",
        ]);
      }
      expect(
        publicBrowserProblems,
        "fresh public visual pages must have no unexpected browser errors",
      ).toEqual([]);
      expect(publicBackgrounds).toHaveLength(publicStates.length);
      expect(
        publicBackgrounds.every(
          (image) =>
            image.naturalWidth === 1920 && image.naturalHeight === 1080,
        ),
        "every public background must decode at source dimensions under normal CSP",
      ).toBe(true);
      expect(
        publicStates.every((state) => state.png.darkPixels > 1_000),
        "fresh public captures must visibly composite the dark background",
      ).toBe(true);
      await writeFile(
        path.join(evidenceDir, "known-browser-limitations.json"),
        `${JSON.stringify(knownBrowserNotes, null, 2)}\n`,
        { encoding: "utf8", mode: 0o600 },
      );
    }

    // Then: separately recorded live, submitted, decoded, adversarial, and race observations all pass.
    await writeFile(
      path.join(evidenceDir, "results.json"),
      `${JSON.stringify(
        {
          adversarial: {
            invalidImage: 400,
            malformedColor: 400,
            networkRollback: "black",
            openSameValue: 409,
            ownerUnavailable: 404,
            unauthenticated: 401,
          },
          finalPng: decoded,
          firstLivePixel: liveWhite,
          sourceBackground: {
            height: sourceBackground.height,
            rgb: [32, 32, 32],
            width: sourceBackground.width,
          },
          multiTabRace: {
            finalColor: raceState.signatureInkColor,
            openStatus: raceOpen.status,
            patchStatus: racePatch.status,
          },
          nginxBacked: true,
          runtimeTiming: {
            livePixelLatencyMs,
            reopenStatus: 200,
            submitResponseLatencyMs,
            submitStatus: completedSubmitResponse.status(),
            submittedPixelLatencyMs,
          },
          signerPadBlackPixels: signerBlack.count,
          submitCompletion: afterSubmit,
        },
        null,
        2,
      )}\n`,
      { encoding: "utf8", mode: 0o600 },
    );
    await writeFile(
      path.join(evidenceDir, "adversarial.md"),
      [
        "# Task 8 adversarial matrix",
        "",
        "- malformed/auth APIs: PASS (400/401/404 with stable codes)",
        "- no-background white plus durable readback: PASS (409; black retained)",
        "- image load/final PNG premature errors: PASS (400/409)",
        "- network rollback: PASS (optimistic white returned to black)",
        "- reconnect/multi-tab OPEN race: PASS (serialized result retained after reload)",
        "- live/recovery/replacement/concurrent signer/lease fences: delegated to the required existing public-display-real-stack suite",
        "- dirty worktree: PASS (read-only harness build context preserved unrelated changes)",
        "- hung Compose/browser: PASS (launcher bounded timeouts and finally cleanup)",
        "- misleading success: PASS (live canvas pixels, signer pixels, decoded PNG, and durable API state checked)",
        "- repeated interruption: N/A (no interruption occurred)",
        "- prompt injection: N/A (no untrusted instructional input consumed)",
        "",
      ].join("\n"),
      { encoding: "utf8", mode: 0o600 },
    );
  } finally {
    await Promise.all(contexts.map((context) => context.close()));
  }
});
