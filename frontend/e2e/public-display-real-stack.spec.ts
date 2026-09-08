import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import {
  expect,
  type Page,
  type Response,
  test,
} from "@playwright/test";
import {
  blackPixelCount,
  canvasData,
  createOpenBoard,
  drawStroke,
  identify,
  parseDraftEventPayloads,
  primaryIdentity,
  readCredentials,
  requiredEnvironment,
  secondaryIdentity,
} from "./realStackSupport.ts";

const evidenceDir = path.resolve(
  process.env.TASK8_EVIDENCE_DIR ?? "../.omo/evidence/public-display-live-ink",
);
function displayUrl(baseUrl: URL, shareUrl: string): URL {
  const value = new URL(shareUrl, baseUrl);
  value.protocol = baseUrl.protocol;
  value.host = baseUrl.host;
  value.pathname = value.pathname.replace(/^\/sign\//u, "/display/");
  return value;
}

function observeDraftResponses(page: Page, output: string[]): void {
  page.on("response", (response) => {
    const pathname = new URL(response.url()).pathname;
    if (pathname.includes("/signing-session/draft"))
      output.push(
        `${response.request().method()} ${pathname} ${response.status()}`,
      );
  });
}

test.describe("@real public display composed contract", () => {
  // allow: SIZE_OK — one ordered real-stack narrative proves cross-context timing contracts.
  test("proves leases, live ink, recovery, concurrency, replacement, and final geometry through nginx", async ({
    browser,
    browserName,
  }) => {
    test.skip(
      process.env.TASK8_REAL_MODE !== "real",
      "requires the isolated Compose stack",
    );
    test.skip(
      browserName !== "chromium",
      "requires Chromium CDP for native SSE revision capture",
    );
    test.setTimeout(150_000);
    await mkdir(evidenceDir, { recursive: true });
    const baseUrl = new URL(requiredEnvironment("TASK8_BASE_URL"));
    const { email, password } = readCredentials();
    const contextOptions = {
      baseURL: baseUrl.toString(),
      ignoreHTTPSErrors: true,
    };
    const adminContext = await browser.newContext({
      ...contextOptions,
      extraHTTPHeaders: { "X-Narae-Client-IP": "127.0.0.1" },
    });
    const displayOneContext = await browser.newContext(contextOptions);
    const displayTwoContext = await browser.newContext(contextOptions);
    const signerContext = await browser.newContext(contextOptions);
    const concurrentSignerContext = await browser.newContext(contextOptions);
    const contexts = [
      adminContext,
      displayOneContext,
      displayTwoContext,
      signerContext,
      concurrentSignerContext,
    ];
    const draftResponses: string[] = [];
    const deniedContentRequests: string[] = [];
    let sseResponse: Response | undefined;
    let displayTwoClaimStatus = 0;
    let forceReplaceStatus = 0;

    try {
      const admin = await adminContext.newPage();
      const displayOne = await displayOneContext.newPage();
      const displayTwo = await displayTwoContext.newPage();
      const signer = await signerContext.newPage();
      const concurrentSigner = await concurrentSignerContext.newPage();
      observeDraftResponses(signer, draftResponses);
      observeDraftResponses(concurrentSigner, draftResponses);
      displayTwo.on("request", (request) => {
        const pathname = new URL(request.url()).pathname;
        if (/\/display\/(?:title|snapshot|background|events)$/u.test(pathname))
          deniedContentRequests.push(pathname);
      });

      const { boardId, shareUrl: configuredShareUrl } = await createOpenBoard(
        admin,
        baseUrl,
        email,
        password,
      );
      const shareUrl = new URL(configuredShareUrl);
      shareUrl.protocol = baseUrl.protocol;
      shareUrl.host = baseUrl.host;
      const signerUrl = shareUrl.toString();
      const publicUrl = displayUrl(baseUrl, signerUrl);
      const draftEventPayloads: string[] = [];
      const devtools = await displayOneContext.newCDPSession(displayOne);
      await devtools.send("Network.enable");
      devtools.on("Network.eventSourceMessageReceived", (event) => {
        if (event.eventName === "signature-draft")
          draftEventPayloads.push(event.data);
      });

      const firstSse = displayOne.waitForResponse((response) =>
        new URL(response.url()).pathname.endsWith("/display/events"),
      );
      await displayOne.goto(publicUrl.toString(), {
        waitUntil: "domcontentloaded",
      });
      await expect(displayOne.getByTestId("full-view-canvas")).toBeVisible();
      sseResponse = await firstSse;

      const deniedClaim = displayTwo.waitForResponse((response) =>
        new URL(response.url()).pathname.endsWith("/display/claim"),
      );
      await displayTwo.goto(publicUrl.toString(), {
        waitUntil: "domcontentloaded",
      });
      expect((await deniedClaim).status()).toBe(409);
      await expect(displayTwo.getByRole("alert")).toContainText("다른 화면");
      expect(deniedContentRequests).toEqual([]);

      expect((await identify(signer, signerUrl, primaryIdentity)).status()).toBe(
        200,
      );
      await expect(signer.getByTestId("public-signer-drawing")).toBeVisible();
      await expect
        .poll(() => draftResponses.some((entry) =>
          entry.startsWith("PUT ") && entry.endsWith(" 200")))
        .toBe(true);

      const failedOfflineDraft = signer.waitForEvent(
        "requestfailed",
        (request) =>
          new URL(request.url()).pathname.endsWith("/signing-session/draft/delta"),
      );
      const draftsBeforeOffline = draftResponses.length;
      await signerContext.setOffline(true);
      await drawStroke(signer, 0.3);
      await failedOfflineDraft;
      await signerContext.setOffline(false);
      await expect
        .poll(() => draftResponses.slice(draftsBeforeOffline).some((entry) =>
          entry.startsWith("PUT ") && entry.endsWith(" 200")), {
          timeout: 25_000,
        })
        .toBe(true);
      const firstSlot = displayOne.locator("[data-slot-id] canvas").first();
      await expect(firstSlot).toBeVisible();
      await expect.poll(() => blackPixelCount(firstSlot)).toBeGreaterThan(25);
      await displayOne.screenshot({
        path: path.join(evidenceDir, "task-8-progressive-black-ink.png"),
        fullPage: true,
      });
      expect(
        draftResponses
          .find((entry) => entry.endsWith(" 200"))
          ?.startsWith("PUT "),
      ).toBe(true);
      expect(
        (await identify(concurrentSigner, signerUrl, primaryIdentity)).status(),
      ).toBe(403);
      await expect(
        concurrentSigner.getByTestId("public-signer-identify"),
      ).toBeVisible();
      await concurrentSignerContext.clearCookies();

      await drawStroke(signer, 0.55);
      await expect
        .poll(() =>
          draftResponses.some(
            (entry) => entry.startsWith("POST ") && entry.endsWith(" 200"),
          ),
        )
        .toBe(true);
      const beforeReconcile = await canvasData(firstSlot);
      let snapshotResponses = 0;
      displayOne.on("response", (response) => {
        if (
          new URL(response.url()).pathname.endsWith("/display/snapshot") &&
          response.status() === 200
        )
          snapshotResponses += 1;
      });
      await displayOneContext.setOffline(true);
      await drawStroke(signer, 0.75);
      await signer.waitForTimeout(300);
      const snapshotsBeforeOnline = snapshotResponses;
      await displayOneContext.setOffline(false);
      await expect
        .poll(() => snapshotResponses, { timeout: 12_000 })
        .toBeGreaterThan(snapshotsBeforeOnline);
      await expect
        .poll(() => canvasData(firstSlot), { timeout: 12_000 })
        .not.toBe(beforeReconcile);

      expect(
        (
          await identify(concurrentSigner, signerUrl, secondaryIdentity)
        ).status(),
      ).toBe(200);
      await expect(
        concurrentSigner.getByTestId("public-signer-drawing"),
      ).toBeVisible();
      await drawStroke(concurrentSigner, 0.45);
      await expect(displayOne.locator("[data-slot-id] canvas")).toHaveCount(2);
      await expect
        .poll(() => blackPixelCount(
          displayOne.locator("[data-slot-id] canvas").last()))
        .toBeGreaterThan(25);

      await signer.getByRole("button", { name: "서명 취소" }).click();
      await expect(signer.getByTestId("public-signer-identify")).toBeVisible();
      await expect(displayOne.locator("[data-slot-id] canvas")).toHaveCount(1);
      await concurrentSigner.getByRole("button", { name: "서명 취소" }).click();
      await expect(
        concurrentSigner.getByTestId("public-signer-identify"),
      ).toBeVisible();
      expect(
        (await identify(concurrentSigner, signerUrl, primaryIdentity)).status(),
      ).toBe(200);
      await expect(
        concurrentSigner.getByTestId("public-signer-drawing"),
      ).toBeVisible();
      const eventsBeforeFinalStroke = draftEventPayloads.length;
      await drawStroke(concurrentSigner, 0.5);
      await expect
        .poll(() => parseDraftEventPayloads(
          draftEventPayloads.slice(eventsBeforeFinalStroke),
        ).some((event) => event.operation === "end"))
        .toBe(true);
      await expect(displayOne.locator("[data-slot-id] canvas")).toHaveCount(1);
      const finalCanvas = displayOne.locator("[data-slot-id] canvas").first();
      await displayOne.evaluate(() => new Promise<void>((resolve) => {
        requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
      }));
      const draftGeometry = await canvasData(finalCanvas);
      expect(await blackPixelCount(finalCanvas)).toBeGreaterThan(25);
      await concurrentSigner.getByRole("button", { name: "서명 제출" }).click();
      await expect(
        concurrentSigner.getByTestId("public-signer-complete"),
      ).toBeVisible();
      await expect.poll(() => canvasData(finalCanvas)).toBe(draftGeometry);
      await displayOne.screenshot({
        path: path.join(evidenceDir, "task-8-final-submit-geometry.png"),
        fullPage: true,
      });

      const events = parseDraftEventPayloads(draftEventPayloads);
      const lastVersion = new Map<string, readonly [number, number]>();
      for (const event of events) {
        const previous = lastVersion.get(event.slotId);
        if (previous !== undefined) {
          expect(
            event.draftEpoch > previous[0] ||
              (event.draftEpoch === previous[0] &&
                event.revision > previous[1]),
          ).toBe(true);
        }
        lastVersion.set(event.slotId, [event.draftEpoch, event.revision]);
      }
      expect(events.length).toBeGreaterThan(5);

      const retainedGeometry = await canvasData(finalCanvas);
      await displayOneContext.setOffline(true);
      await displayOne.waitForTimeout(31_500);
      expect(await canvasData(finalCanvas)).toBe(retainedGeometry);
      const reclaimedClaim = displayTwo.waitForResponse((response) =>
        new URL(response.url()).pathname.endsWith("/display/claim"),
      );
      await displayTwo.reload({ waitUntil: "domcontentloaded" });
      displayTwoClaimStatus = (await reclaimedClaim).status();
      expect(displayTwoClaimStatus).toBe(204);
      await expect(displayTwo.getByTestId("full-view-canvas")).toBeVisible();
      await displayTwo.screenshot({
        path: path.join(evidenceDir, "task-8-display-expiry-reclaimed.png"),
        fullPage: true,
      });

      const sharePanel = admin.getByRole("region", { name: "공유" });
      await sharePanel
        .getByRole("button", { name: "행사장 화면 교체" })
        .click();
      await expect(
        admin.getByText(/현재 행사장 화면의 표시 연결을 종료/u),
      ).toBeVisible();
      const forceResponse = admin.waitForResponse(
        (response) =>
          new URL(response.url()).pathname ===
          `/api/v1/admin/boards/${boardId}/display/force-replace`,
      );
      await admin
        .getByRole("button", { exact: true, name: "화면 교체" })
        .click();
      forceReplaceStatus = (await forceResponse).status();
      expect(forceReplaceStatus).toBe(204);
      await expect(displayTwo.getByRole("alert")).toContainText(
        "다른 화면으로 전환",
      );
      await displayTwo.screenshot({
        path: path.join(evidenceDir, "task-8-confirmed-force-replacement.png"),
        fullPage: true,
      });

      await writeFile(
        path.join(evidenceDir, "task-8-real-contract.json"),
        `${JSON.stringify(
          {
            contexts: { admin: 1, displays: 2, signers: 2 },
            deniedContentRequestsBeforeAuthorization: 0,
            displayExpirySeconds: 30,
            displayTwoClaimStatus,
            draftEventCount: events.length,
            draftResponses,
            finalGeometryMatched: true,
            forceReplaceStatus,
            nginxSse: {
              contentType: sseResponse.headers()["content-type"],
              status: sseResponse.status(),
              xAccelBuffering: sseResponse.headers()["x-accel-buffering"],
            },
            snapshotResponsesAfterOffline:
              snapshotResponses - snapshotsBeforeOnline,
          },
          null,
          2,
        )}\n`,
      );
      expect(sseResponse.status()).toBe(200);
      expect(sseResponse.headers()["content-type"]).toContain(
        "text/event-stream",
      );
      expect(sseResponse.headers()["x-accel-buffering"]).toBe("no");
    } finally {
      await Promise.all(contexts.map((context) => context.close()));
    }
  });
});
