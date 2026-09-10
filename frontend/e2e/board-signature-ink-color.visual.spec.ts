import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import type { Page } from "@playwright/test";
import { expect, test } from "@playwright/test";
import {
  captureVisualState,
  type VisualState,
} from "./board-signature-ink-color.visual.ts";

const desktopViewport = { height: 720, width: 1280 } as const;

type PngDimensions = {
  readonly height: number;
  readonly width: number;
};

function decodePngDimensions(bytes: Buffer): PngDimensions {
  expect(bytes.subarray(0, 8)).toEqual(
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
  );
  expect(bytes.subarray(12, 16).toString("ascii")).toBe("IHDR");
  return { height: bytes.readUInt32BE(20), width: bytes.readUInt32BE(16) };
}

async function mountSyntheticAdminFixture(page: Page): Promise<void> {
  await page.goto(
    "data:text/html,<style>html,body{margin:0;height:1600px}main{height:1600px;background:rgb(32,32,32)}</style><main aria-label=synthetic-admin-fixture></main>",
  );
}

async function writeCaptureManifest(
  evidenceDir: string,
  state: VisualState,
): Promise<void> {
  await writeFile(
    path.join(evidenceDir, `${state.captureMode}-manifest.json`),
    `${JSON.stringify(state, null, 2)}\n`,
    { encoding: "utf8", mode: 0o600 },
  );
}

async function expectNativeDesktopCapture(
  evidenceDir: string,
  state: VisualState,
): Promise<void> {
  const decoded = decodePngDimensions(
    await readFile(path.join(evidenceDir, state.screenshot)),
  );
  expect(decoded).toEqual({ height: 720, width: 840 });
  expect(state.png).toMatchObject(decoded);
  expect(state.clip).toEqual({ height: 720, width: 840, x: 0, y: 0 });
  expect(state.privacy.sensitiveNodesInCapture).toBe(0);
}

test("Given a resting desktop admin fixture When captured Then the decoded PNG matches its viewport clip manifest", async ({
  page,
}, testInfo) => {
  // Given: a synthetic, safe admin-shaped page at the native desktop viewport.
  const evidenceDir = testInfo.outputPath("visual-captures");
  await mkdir(evidenceDir, { recursive: true });
  await mountSyntheticAdminFixture(page);

  // When: the rest frame is captured.
  const state = await captureVisualState(
    page,
    evidenceDir,
    "admin-draft-black",
    [],
    desktopViewport,
  );
  await writeCaptureManifest(evidenceDir, state);

  // Then: independently decoded PNG bytes agree with the public manifest.
  await expectNativeDesktopCapture(evidenceDir, state);
});

test("Given a scrolled desktop admin fixture When captured Then the decoded PNG matches its viewport clip manifest", async ({
  page,
}, testInfo) => {
  // Given: the same fixture at a positive document scroll position.
  const evidenceDir = testInfo.outputPath("visual-captures");
  await mkdir(evidenceDir, { recursive: true });
  await mountSyntheticAdminFixture(page);
  await page.evaluate(() => window.scrollTo(0, 213));
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(213);

  // When: the scroll frame is captured.
  const state = await captureVisualState(
    page,
    evidenceDir,
    "admin-draft-black",
    [],
    desktopViewport,
    "scroll",
  );
  await writeCaptureManifest(evidenceDir, state);

  // Then: independently decoded PNG bytes agree with the public manifest.
  await expectNativeDesktopCapture(evidenceDir, state);
});

test("Given a desktop admin fixture When rest, scroll, and rest captures run Then each PNG retains its native viewport contract", async ({
  page,
}, testInfo) => {
  // Given: a stable fixture before a rest-scroll-rest capture sequence.
  const evidenceDir = testInfo.outputPath("visual-captures");
  await mkdir(evidenceDir, { recursive: true });
  await mountSyntheticAdminFixture(page);

  // When: captures traverse rest, a positive document scroll, and rest again.
  const before = await captureVisualState(
    page,
    evidenceDir,
    "admin-stale-before",
    [],
    desktopViewport,
  );
  await page.evaluate(() => window.scrollTo(0, 213));
  const during = await captureVisualState(
    page,
    evidenceDir,
    "admin-stale-scroll",
    [],
    desktopViewport,
    "scroll",
  );
  const after = await captureVisualState(
    page,
    evidenceDir,
    "admin-stale-after",
    [],
    desktopViewport,
  );

  // Then: every independently decoded PNG is native-sized and rest resets scroll.
  await expectNativeDesktopCapture(evidenceDir, before);
  await expectNativeDesktopCapture(evidenceDir, during);
  await expectNativeDesktopCapture(evidenceDir, after);
  expect(after.scrollY).toBe(0);
});

test("Given a visible sensitive admin node When captured Then the privacy guard rejects before saving and a later capture recovers", async ({
  page,
}, testInfo) => {
  // Given: a fixture whose share panel intersects the viewport capture rectangle.
  const evidenceDir = testInfo.outputPath("visual-captures");
  await mkdir(evidenceDir, { recursive: true });
  await page.goto(
    "data:text/html,<style>html,body{margin:0;height:1600px}.editor-share-url{height:40px;left:0;position:absolute;top:400px;width:100px}</style><div class=editor-share-url>redacted</div>",
  );
  await page.evaluate(() => window.scrollTo(0, 213));

  // When: the desktop scroll capture is requested.
  const capture = captureVisualState(
    page,
    evidenceDir,
    "admin-draft-black",
    [],
    desktopViewport,
    "scroll",
  );

  // Then: capture fails closed before sensitive content can be written.
  await expect(capture).rejects.toThrow(
    "Task 8 visual capture intersects share URL or QR",
  );
  await expect(
    readFile(path.join(evidenceDir, "admin-draft-black-scroll-1280.png")),
  ).rejects.toThrow();
  await mountSyntheticAdminFixture(page);
  await page.evaluate(() => window.scrollTo(0, 213));
  const recovered = await captureVisualState(
    page,
    evidenceDir,
    "admin-privacy-recovery",
    [],
    desktopViewport,
    "scroll",
  );
  await expectNativeDesktopCapture(evidenceDir, recovered);
});
