import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { chromium, expect, test } from "@playwright/test";
import { z } from "zod";
import {
  dispatchPenStroke,
  signatureCanvasBox,
} from "../src/features/signing/pad/e2eDriver.ts";

const pointSchema = z.object({ x: z.number().int(), y: z.number().int() });
const fullDraftSchema = z.object({
  strokes: z.array(z.object({ points: z.array(pointSchema) })),
  version: z.literal(1),
});
const deltaSchema = z.object({
  clientSequence: z.number().int(),
  draftEpoch: z.number().int(),
  operation: z.enum(["append", "begin", "end"]),
  points: z.array(pointSchema),
  revision: z.number().int(),
  strokeIndex: z.number().int(),
});

test.use({ trace: "off" });
test("failed signer deltas recover with a lossless full reset before resuming", async ({
  browserName,
}, testInfo) => {
  test.skip(browserName !== "chromium", "Retail Chrome evidence");
  const evidenceDir = path.resolve(testInfo.config.rootDir, "../../.omo/evidence/public-display-live-ink");
  await mkdir(evidenceDir, { recursive: true });
  const browser = await chromium.launch({ channel: "chrome" });
  const context = await browser.newContext({
    baseURL: testInfo.project.use.baseURL,
    viewport: { height: 720, width: 900 },
  });
  const page = await context.newPage();
  const cdp = await context.newCDPSession(page);
  const requests: { body: string; method: string; path: string; result: string }[] = [];
  const fullDrafts: z.infer<typeof fullDraftSchema>[] = [];
  const resumedDeltas: z.infer<typeof deltaSchema>[] = [];
  let activeRequests = 0;
  let deltaAttempts = 0;
  let maxActiveRequests = 0;
  let revision = 0;

  await context.tracing.start({ screenshots: true, snapshots: true });
  try {
    await page.route("**/api/v1/public/signing-session/draft{,/**}", async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      const record = {
        body: request.postData() ?? "",
        method: request.method(),
        path: url.pathname,
        result: "pending",
      };
      requests.push(record);
      activeRequests += 1;
      maxActiveRequests = Math.max(maxActiveRequests, activeRequests);
      if (url.pathname.endsWith("/delta")) {
        deltaAttempts += 1;
        if (deltaAttempts === 1) {
          await new Promise((resolve) => setTimeout(resolve, 75));
          record.result = "failed";
          activeRequests -= 1;
          await route.abort("failed");
          return;
        }
        const delta = deltaSchema.parse(JSON.parse(record.body));
        resumedDeltas.push(delta);
        revision += 1;
        record.result = "ok";
        activeRequests -= 1;
        await route.fulfill({ contentType: "application/json", json: { draftEpoch: 4, revision } });
        return;
      }
      fullDrafts.push(fullDraftSchema.parse(JSON.parse(record.body)));
      revision = 0;
      record.result = "ok";
      activeRequests -= 1;
      await route.fulfill({ contentType: "application/json", json: { draftEpoch: 4, revision } });
    });
    await page.goto("/e2e/harness/signature-pad.html?draft-sync=1");
    await context.addCookies([{ name: "XSRF-TOKEN", url: page.url(), value: "e2e" }]);
    const canvas = page.getByTestId("signer-canvas");
    const box = await signatureCanvasBox(page);

    await dispatchPenStroke(cdp, [
      { x: box.x + box.width * 0.25, y: box.y + box.height * 0.25 },
      { x: box.x + box.width * 0.5, y: box.y + box.height * 0.5 },
      { x: box.x + box.width * 0.75, y: box.y + box.height * 0.75 },
    ]);
    await expect.poll(() => fullDrafts.length).toBe(1);
    await dispatchPenStroke(cdp, [
      { x: box.x + box.width * 0.2, y: box.y + box.height * 0.2 },
      { x: box.x + box.width * 0.4, y: box.y + box.height * 0.4 },
      { x: box.x + box.width * 0.6, y: box.y + box.height * 0.6 },
    ]);
    await expect.poll(() => resumedDeltas.some((delta) => delta.operation === "end")).toBe(true);

    expect(maxActiveRequests).toBe(1);
    expect(fullDrafts[0]?.strokes[0]?.points).toEqual([
      { x: 250_000, y: 250_000 },
      { x: 500_000, y: 500_000 },
      { x: 750_000, y: 750_000 },
    ]);
    expect(resumedDeltas.flatMap((delta) => delta.points)).toEqual([
      { x: 200_000, y: 200_000 },
      { x: 400_000, y: 400_000 },
      { x: 600_000, y: 600_000 },
    ]);
    expect(requests[0]).toEqual(expect.objectContaining({ method: "POST", result: "failed" }));
    expect(requests[1]).toEqual(expect.objectContaining({ method: "PUT", result: "ok" }));
    expect(requests.slice(2).every((request) => request.method === "POST")).toBe(true);
    expect(resumedDeltas.map((delta) => delta.clientSequence)).toEqual([1, 2, 3, 4]);
    await expect(canvas).toHaveAttribute("data-point-count", "6");
    await page.screenshot({ path: path.join(evidenceDir, "task-4-manual-recovery.png") });
    await page.getByRole("button", { name: "서명 제출" }).click();
    await expect(page.getByTestId("payload-summary")).toHaveText(
      "v=1;strokes=2;points=6;checksum=129600000;matching=0",
    );
  } finally {
    await writeFile(
      path.join(evidenceDir, "task-4-network-summary.json"),
      `${JSON.stringify({ maxActiveRequests, requests }, null, 2)}\n`,
    );
    await context.tracing.stop({ path: path.join(evidenceDir, "task-4-manual-recovery-trace.zip") });
    await context.close();
    await browser.close();
  }
});
