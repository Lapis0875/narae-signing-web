import { writeFile } from "node:fs/promises";
import path from "node:path";
import { expect, type Page, test } from "@playwright/test";
import {
  createDraftBoard,
  darkBackground,
  login,
  uploadBackground,
} from "./board-signature-ink-color.support.ts";
import { readCredentials, requiredEnvironment } from "./realStackSupport.ts";

type Rectangle = {
  readonly bottom: number;
  readonly left: number;
  readonly right: number;
  readonly top: number;
};

type ControlMeasurement = {
  readonly accessibleName: string;
  readonly rect: Rectangle;
};

type LabelMeasurement = {
  readonly contrastRatio: number;
  readonly controls: readonly ControlMeasurement[];
  readonly firstGlyph: string;
  readonly firstGlyphRect: Rectangle;
  readonly firstGlyphVisible: boolean;
  readonly fullText: string;
  readonly labelRect: Rectangle;
  readonly rect: Rectangle;
  readonly slotBackground: string;
  readonly slotRect: Rectangle;
  readonly suffix: string;
  readonly suffixRect: Rectangle;
  readonly suffixVisible: boolean;
};

type PixelMeasurement = {
  readonly changedGlyphPixels: number;
  readonly glyphPixels: number;
  readonly hiddenControlCount: number;
  readonly hiddenPath: string;
  readonly hiddenSuffixPath: string;
  readonly missingGlyphPixels: number;
  readonly shownControlCount: number;
  readonly shownPath: string;
  readonly shownSuffixPath: string;
};

type ControlStyleState = {
  readonly computedVisibility: string;
  readonly inlinePriority: string;
  readonly inlineVisibility: string;
};

type CaptureSuffixOptions = {
  readonly interruptAfterHiddenState?: boolean;
};

const darkBoardRgb = [32, 32, 32] as const;

function evidenceDirectory() {
  return path.resolve(
    process.env.MOBILE_SLOT_EVIDENCE_DIR ??
      requiredEnvironment("TASK8_EVIDENCE_DIR"),
  );
}

async function openSyntheticBoard(
  page: Page,
  names: readonly string[] = ["시험 서명자 A", "시험 서명자 B"],
) {
  const baseUrl = new URL(requiredEnvironment("TASK8_BASE_URL"));
  const { email, password } = readCredentials();
  await login(page, baseUrl, email, password);
  const boardId = await createDraftBoard(page, "모바일 슬롯 라벨 합성 검증");
  const editor = page.getByRole("region", { name: "명단 편집" });
  for (const name of names) {
    await editor.locator("#new-organization").fill("합성 조직");
    await editor.locator("#new-job").fill("검증");
    await editor.locator("#new-name").fill(name);
    await editor.getByRole("button", { name: "명단 추가" }).click();
    await page.getByRole("button", { name: `${name} 배치` }).click();
    await page
      .getByRole("button", { name: `${name} 이동` })
      .waitFor({ state: "visible" });
  }
  return boardId;
}

async function measureLabels(
  page: Page,
  width: number,
  boardRgb: readonly [number, number, number] = [240, 243, 248],
): Promise<readonly LabelMeasurement[]> {
  await page.setViewportSize({ width, height: width === 375 ? 667 : 720 });
  await page.locator(".editor-canvas-panel").scrollIntoViewIfNeeded();
  return page.locator(".slot-move").evaluateAll(
    (buttons, measuredBoardRgb) =>
      buttons.slice(0, 2).map((button) => {
        const fullText = button.textContent ?? "";
        const textNodes: Text[] = [];
        const walker = document.createTreeWalker(button, NodeFilter.SHOW_TEXT);
        for (
          let current = walker.nextNode();
          current !== null;
          current = walker.nextNode()
        ) {
          if (current instanceof Text) textNodes.push(current);
        }
        const firstTextNode = textNodes[0];
        const textNode = textNodes.at(-1);
        const slot = button.parentElement;
        const label = button.querySelector(".slot-label");
        if (
          firstTextNode === undefined ||
          textNode === undefined ||
          slot === null ||
          label === null
        )
          throw new Error("Slot label DOM is incomplete");
        const firstGlyphRange = document.createRange();
        firstGlyphRange.setStart(firstTextNode, 0);
        firstGlyphRange.setEnd(
          firstTextNode,
          Math.min(1, firstTextNode.length),
        );
        const firstGlyphRect = firstGlyphRange.getBoundingClientRect();
        const suffixRange = document.createRange();
        suffixRange.setStart(textNode, Math.max(0, textNode.length - 1));
        suffixRange.setEnd(textNode, textNode.length);
        const suffixRect = suffixRange.getBoundingClientRect();
        const rect = button.getBoundingClientRect();
        const labelRect = label.getBoundingClientRect();
        const slotRect = slot.getBoundingClientRect();
        const colorChannels = (
          getComputedStyle(label).color.match(/[0-9.]+/gu) ?? []
        )
          .slice(0, 3)
          .map(Number);
        const relativeLuminance = (channels: readonly number[]) =>
          channels.reduce((sum, channel, index) => {
            const normalized = channel / 255;
            const linear =
              normalized <= 0.04045
                ? normalized / 12.92
                : ((normalized + 0.055) / 1.055) ** 2.4;
            return sum + linear * ([0.2126, 0.7152, 0.0722][index] ?? 0);
          }, 0);
        const foregroundLuminance = relativeLuminance(colorChannels);
        const boardLuminance = relativeLuminance(measuredBoardRgb);
        const contrastRatio =
          (Math.max(foregroundLuminance, boardLuminance) + 0.05) /
          (Math.min(foregroundLuminance, boardLuminance) + 0.05);
        const controls = Array.from(
          document.querySelectorAll<HTMLButtonElement>(
            ".slot-overlay .slot-actions button, .slot-overlay .slot-resize",
          ),
        ).map((control) => {
          const controlRect = control.getBoundingClientRect();
          return {
            accessibleName: control.getAttribute("aria-label") ?? "",
            rect: {
              bottom: controlRect.bottom,
              left: controlRect.left,
              right: controlRect.right,
              top: controlRect.top,
            },
          };
        });
        return {
          contrastRatio,
          controls,
          firstGlyph: fullText.slice(0, 1),
          firstGlyphRect: {
            bottom: firstGlyphRect.bottom,
            left: firstGlyphRect.left,
            right: firstGlyphRect.right,
            top: firstGlyphRect.top,
          },
          firstGlyphVisible:
            firstGlyphRect.left >= rect.left &&
            firstGlyphRect.right <= rect.right &&
            firstGlyphRect.top >= rect.top &&
            firstGlyphRect.bottom <= rect.bottom,
          fullText,
          labelRect: {
            bottom: labelRect.bottom,
            left: labelRect.left,
            right: labelRect.right,
            top: labelRect.top,
          },
          rect: {
            bottom: rect.bottom,
            left: rect.left,
            right: rect.right,
            top: rect.top,
          },
          slotBackground: getComputedStyle(slot).backgroundColor,
          slotRect: {
            bottom: slotRect.bottom,
            left: slotRect.left,
            right: slotRect.right,
            top: slotRect.top,
          },
          suffix: fullText.slice(-1),
          suffixRect: {
            bottom: suffixRect.bottom,
            left: suffixRect.left,
            right: suffixRect.right,
            top: suffixRect.top,
          },
          suffixVisible:
            suffixRect.left >= rect.left &&
            suffixRect.right <= rect.right &&
            suffixRect.top >= rect.top &&
            suffixRect.bottom <= rect.bottom,
        };
      }),
    boardRgb,
  );
}

async function waitForPaint(page: Page) {
  await page.evaluate(
    () =>
      new Promise<void>((resolve) => {
        requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
      }),
  );
}

async function readControlStyles(
  page: Page,
): Promise<readonly ControlStyleState[]> {
  return page
    .locator(".slot-overlay .slot-actions button, .slot-overlay .slot-resize")
    .evaluateAll((elements) =>
      elements.map((element) => {
        if (!(element instanceof HTMLElement))
          throw new Error("Slot control is not an HTML element");
        return {
          computedVisibility: getComputedStyle(element).visibility,
          inlinePriority: element.style.getPropertyPriority("visibility"),
          inlineVisibility: element.style.getPropertyValue("visibility"),
        };
      }),
    );
}

async function requireControlVisibility(
  page: Page,
  visibility: "hidden" | "visible",
) {
  await waitForPaint(page);
  const states = await readControlStyles(page);
  if (
    states.length === 0 ||
    states.some((state) => state.computedVisibility !== visibility)
  ) {
    throw new Error(
      `Expected every slot control to be ${visibility} before capture`,
    );
  }
  return states.length;
}

async function setControlVisibility(
  page: Page,
  visibility: "hidden" | "visible",
) {
  await page
    .locator(".slot-overlay .slot-actions button, .slot-overlay .slot-resize")
    .evaluateAll((elements, requestedVisibility) => {
      for (const element of elements) {
        if (!(element instanceof HTMLElement))
          throw new Error("Slot control is not an HTML element");
        element.style.setProperty("visibility", requestedVisibility);
      }
    }, visibility);
  return requireControlVisibility(page, visibility);
}

async function restoreControlStyles(
  page: Page,
  originalStates: readonly ControlStyleState[],
) {
  await page
    .locator(".slot-overlay .slot-actions button, .slot-overlay .slot-resize")
    .evaluateAll((elements, states) => {
      if (elements.length !== states.length)
        throw new Error("Slot controls changed during suffix capture");
      for (const [index, element] of elements.entries()) {
        if (!(element instanceof HTMLElement))
          throw new Error("Slot control is not an HTML element");
        const state = states[index];
        if (state === undefined)
          throw new Error("Slot control state is missing");
        if (state.inlineVisibility === "")
          element.style.removeProperty("visibility");
        else
          element.style.setProperty(
            "visibility",
            state.inlineVisibility,
            state.inlinePriority,
          );
      }
    }, originalStates);
  await waitForPaint(page);
  const restoredStates = await readControlStyles(page);
  if (
    restoredStates.some(
      (state, index) =>
        state.computedVisibility !== originalStates[index]?.computedVisibility,
    )
  ) {
    throw new Error("Slot control visibility was not restored");
  }
}

async function captureSuffixPixels(
  page: Page,
  labelIndex: number,
  evidenceDir: string,
  artifactName: string,
  options: CaptureSuffixOptions = {},
): Promise<PixelMeasurement> {
  const suffix = page.locator(".slot-label-suffix").nth(labelIndex);
  const suffixClip = await suffix.boundingBox();
  if (suffixClip === null) throw new Error("Slot suffix has no paint box");
  const overlay = suffix.locator(
    "xpath=ancestor::*[contains(concat(' ', normalize-space(@class), ' '), ' slot-overlay ')][1]",
  );
  const actionClip = await overlay.locator(".slot-actions").boundingBox();
  const resizeClip = await overlay.locator(".slot-resize").boundingBox();
  if (actionClip === null || resizeClip === null)
    throw new Error("Slot controls have no paint box");
  const left = Math.min(suffixClip.x, actionClip.x, resizeClip.x);
  const top = Math.min(suffixClip.y, actionClip.y, resizeClip.y);
  const right = Math.max(
    suffixClip.x + suffixClip.width,
    actionClip.x + actionClip.width,
    resizeClip.x + resizeClip.width,
  );
  const bottom = Math.max(
    suffixClip.y + suffixClip.height,
    actionClip.y + actionClip.height,
    resizeClip.y + resizeClip.height,
  );
  const stateClip = {
    height: bottom - top,
    width: right - left,
    x: left,
    y: top,
  };
  const shownPath = path.join(evidenceDir, `${artifactName}-shown.png`);
  const hiddenPath = path.join(
    evidenceDir,
    `${artifactName}-controls-hidden.png`,
  );
  const shownSuffixPath = path.join(
    evidenceDir,
    `${artifactName}-suffix-shown.png`,
  );
  const hiddenSuffixPath = path.join(
    evidenceDir,
    `${artifactName}-suffix-controls-hidden.png`,
  );
  const originalStates = await readControlStyles(page);
  let hidden: Buffer;
  let hiddenControlCount = 0;
  let shown: Buffer;
  let shownControlCount = 0;
  try {
    shownControlCount = await setControlVisibility(page, "visible");
    await page.screenshot({ clip: stateClip, path: shownPath });
    await requireControlVisibility(page, "visible");
    shown = await page.screenshot({ clip: suffixClip, path: shownSuffixPath });
    await requireControlVisibility(page, "visible");
    hiddenControlCount = await setControlVisibility(page, "hidden");
    if (options.interruptAfterHiddenState === true)
      throw new Error("Forced suffix capture interruption");
    await page.screenshot({ clip: stateClip, path: hiddenPath });
    await requireControlVisibility(page, "hidden");
    hidden = await page.screenshot({
      clip: suffixClip,
      path: hiddenSuffixPath,
    });
    await requireControlVisibility(page, "hidden");
  } finally {
    await restoreControlStyles(page, originalStates);
  }
  const pixels = await page.evaluate(
    async ({ hiddenBase64, shownBase64 }) => {
      const decode = async (base64: string) =>
        new Promise<ImageData>((resolve, reject) => {
          const image = new Image();
          image.addEventListener(
            "load",
            () => {
              const canvas = document.createElement("canvas");
              canvas.width = image.naturalWidth;
              canvas.height = image.naturalHeight;
              const context = canvas.getContext("2d");
              if (context === null) {
                reject(new Error("Suffix pixel canvas context is unavailable"));
                return;
              }
              context.drawImage(image, 0, 0);
              resolve(context.getImageData(0, 0, canvas.width, canvas.height));
            },
            { once: true },
          );
          image.addEventListener(
            "error",
            () => reject(new Error("Suffix pixel screenshot failed to decode")),
            { once: true },
          );
          image.src = `data:image/png;base64,${base64}`;
        });
      const hiddenImage = await decode(hiddenBase64);
      const shownImage = await decode(shownBase64);
      if (
        hiddenImage.width !== shownImage.width ||
        hiddenImage.height !== shownImage.height
      )
        throw new Error("Suffix pixel crops have different dimensions");
      let changedGlyphPixels = 0;
      let glyphPixels = 0;
      let missingGlyphPixels = 0;
      for (let index = 0; index < hiddenImage.data.length; index += 4) {
        const hiddenRed = hiddenImage.data[index] ?? 255;
        const hiddenGreen = hiddenImage.data[index + 1] ?? 255;
        const hiddenBlue = hiddenImage.data[index + 2] ?? 255;
        const hiddenAlpha = hiddenImage.data[index + 3] ?? 0;
        const shownRed = shownImage.data[index] ?? 255;
        const shownGreen = shownImage.data[index + 1] ?? 255;
        const shownBlue = shownImage.data[index + 2] ?? 255;
        const shownAlpha = shownImage.data[index + 3] ?? 0;
        const hiddenIsGlyph =
          hiddenAlpha > 0 &&
          hiddenRed < 160 &&
          hiddenGreen < 160 &&
          hiddenBlue < 160;
        const shownIsGlyph =
          shownAlpha > 0 &&
          shownRed < 160 &&
          shownGreen < 160 &&
          shownBlue < 160;
        const difference =
          Math.abs(hiddenRed - shownRed) +
          Math.abs(hiddenGreen - shownGreen) +
          Math.abs(hiddenBlue - shownBlue);
        if ((hiddenIsGlyph || shownIsGlyph) && difference > 30)
          changedGlyphPixels += 1;
        if (hiddenIsGlyph) {
          glyphPixels += 1;
          if (difference > 30) missingGlyphPixels += 1;
        }
      }
      return { changedGlyphPixels, glyphPixels, missingGlyphPixels };
    },
    {
      hiddenBase64: hidden.toString("base64"),
      shownBase64: shown.toString("base64"),
    },
  );
  return {
    ...pixels,
    hiddenControlCount,
    hiddenPath: path.basename(hiddenPath),
    hiddenSuffixPath: path.basename(hiddenSuffixPath),
    shownControlCount,
    shownPath: path.basename(shownPath),
    shownSuffixPath: path.basename(shownSuffixPath),
  };
}

async function captureSuffixesSequentially(
  page: Page,
  labelCount: number,
  evidenceDir: string,
  artifactName: string,
) {
  const measurements: PixelMeasurement[] = [];
  for (let labelIndex = 0; labelIndex < labelCount; labelIndex += 1) {
    measurements.push(
      await captureSuffixPixels(
        page,
        labelIndex,
        evidenceDir,
        `${artifactName}-${labelIndex}`,
      ),
    );
  }
  return measurements;
}

function rectanglesIntersect(first: Rectangle, second: Rectangle) {
  return (
    first.left < second.right &&
    second.left < first.right &&
    first.top < second.bottom &&
    second.top < first.bottom
  );
}

function rectangleContains(outer: Rectangle, inner: Rectangle) {
  return (
    inner.left >= outer.left &&
    inner.right <= outer.right &&
    inner.top >= outer.top &&
    inner.bottom <= outer.bottom
  );
}

function assertPaintSafety(
  labels: readonly [LabelMeasurement, LabelMeasurement],
  pixels: readonly PixelMeasurement[],
) {
  expect(
    labels.every(
      (label) =>
        label.rect.right - label.rect.left >= 44 &&
        label.rect.bottom - label.rect.top >= 44,
    ),
  ).toBe(true);
  expect(labels.every((label) => label.controls.length === 4)).toBe(true);
  expect(
    labels.every((label) =>
      label.controls.every((control) => control.accessibleName.length > 0),
    ),
  ).toBe(true);
  expect(
    labels.every((label) =>
      label.controls.every(
        (control) =>
          control.rect.right - control.rect.left === 44 &&
          control.rect.bottom - control.rect.top === 44,
      ),
    ),
  ).toBe(true);
  expect(
    labels.every((label) =>
      label.controls.every(
        (control) => !rectanglesIntersect(label.suffixRect, control.rect),
      ),
    ),
  ).toBe(true);
  expect(
    pixels.every(
      (measurement) =>
        measurement.shownControlCount === 4 &&
        measurement.hiddenControlCount === 4,
    ),
  ).toBe(true);
  expect(
    pixels.every(
      (measurement) =>
        measurement.glyphPixels > 0 &&
        measurement.missingGlyphPixels === 0 &&
        measurement.changedGlyphPixels === 0,
    ),
  ).toBe(true);
}

function requireTwoLabels(
  labels: readonly LabelMeasurement[],
): readonly [LabelMeasurement, LabelMeasurement] {
  const first = labels[0];
  const second = labels[1];
  if (first === undefined || second === undefined)
    throw new Error("Expected two slot labels");
  return [first, second];
}

test("Given a desktop admin canvas When two synthetic labels render Then their complete text remains distinct", async ({
  page,
}) => {
  const boardId = await openSyntheticBoard(page);
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto(`/boards/${boardId}/edit`);
  const labels = requireTwoLabels(await measureLabels(page, 1280));

  expect(labels.map((label) => label.fullText)).toEqual([
    "시험 서명자 A",
    "시험 서명자 B",
  ]);
  expect(labels.every((label) => label.suffixVisible)).toBe(true);
  expect(rectanglesIntersect(labels[0].rect, labels[1].rect)).toBe(false);
});

test("Given a dark responsive canvas When two synthetic labels render Then their endpoints and contrast remain visible", async ({
  page,
}) => {
  const boardId = await openSyntheticBoard(page);
  const background = await darkBackground(page);
  await uploadBackground(page, boardId, background.buffer);
  await page.goto(`/boards/${boardId}/edit`);
  const evidenceDir = evidenceDirectory();
  const states = [];
  for (const [iteration, width] of [375, 768, 1280, 375].entries()) {
    const labels = requireTwoLabels(
      await measureLabels(page, width, darkBoardRgb),
    );
    const pixels = await captureSuffixesSequentially(
      page,
      labels.length,
      evidenceDir,
      `normal-${iteration}-${width}`,
    );
    await page
      .locator(".slot-overlay")
      .first()
      .evaluate((node) => node.setAttribute("data-testid", "slot-overlay"));
    await page
      .locator('[data-testid="slot-overlay"]')
      .screenshot({ path: path.join(evidenceDir, `slot-${width}.png`) });
    if (width === 375) {
      await page
        .locator(".editor-canvas")
        .screenshot({ path: path.join(evidenceDir, "admin-canvas-375.png") });
    }
    const noHorizontalOverflow = await page.evaluate(
      (viewportWidth) => document.documentElement.scrollWidth <= viewportWidth,
      width,
    );
    states.push({
      labels,
      noHorizontalOverflow,
      pixels,
      viewport: { height: width === 375 ? 667 : 720, width },
    });
    await writeFile(
      path.join(evidenceDir, "measurements.json"),
      `${JSON.stringify({ states }, null, 2)}\n`,
      { encoding: "utf8", mode: 0o600 },
    );
    expect(labels.map((label) => label.suffix)).toEqual(["A", "B"]);
    expect(labels.map((label) => label.firstGlyph)).toEqual(["시", "시"]);
    expect(labels.every((label) => label.firstGlyphVisible)).toBe(true);
    expect(labels.every((label) => label.suffixVisible)).toBe(true);
    expect(labels.every((label) => label.contrastRatio >= 4.5)).toBe(true);
    expect(rectanglesIntersect(labels[0].rect, labels[1].rect)).toBe(false);
    expect(noHorizontalOverflow).toBe(true);
    expect(
      labels.every((label) => label.slotBackground === "rgba(0, 0, 0, 0)"),
    ).toBe(true);
    assertPaintSafety(labels, pixels);
  }
  await page.goto("/boards");
  await expect(page.locator(".slot-overlay")).toHaveCount(0);
});

test("Given long CJK and unbroken labels When the responsive canvas renders Then labels remain contained", async ({
  page,
}) => {
  const names = [`${"긴이름".repeat(10)}끝`, `${"UNBROKEN".repeat(4)}Z`];
  const boardId = await openSyntheticBoard(page, names);
  const background = await darkBackground(page);
  await uploadBackground(page, boardId, background.buffer);
  await page.goto(`/boards/${boardId}/edit`);
  const evidenceDir = evidenceDirectory();
  const states = [];
  for (const [iteration, width] of [375, 768, 1280, 375].entries()) {
    const labels = requireTwoLabels(
      await measureLabels(page, width, darkBoardRgb),
    );
    const pixels = await captureSuffixesSequentially(
      page,
      labels.length,
      evidenceDir,
      `stress-${iteration}-${width}`,
    );
    await page
      .locator(".slot-overlay")
      .first()
      .evaluate((node) => node.setAttribute("data-testid", "slot-overlay"));
    await page
      .locator('[data-testid="slot-overlay"]')
      .screenshot({ path: path.join(evidenceDir, `stress-slot-${width}.png`) });
    await page.locator(".editor-canvas").screenshot({
      path: path.join(evidenceDir, `stress-canvas-${width}.png`),
    });
    const layout = await page.evaluate(
      (viewportWidth) => ({
        documentWidth: document.documentElement.scrollWidth,
        overflow: Array.from(
          document.querySelectorAll<HTMLElement>("body *"),
        ).flatMap((element) => {
          const rect = element.getBoundingClientRect();
          return rect.right > viewportWidth
            ? [
                {
                  className: element.className,
                  id: element.id,
                  right: rect.right,
                  tagName: element.tagName,
                  width: rect.width,
                },
              ]
            : [];
        }),
      }),
      width,
    );

    states.push({
      labels,
      layout,
      pixels,
      viewport: { height: width === 375 ? 667 : 720, width },
    });
  }
  await writeFile(
    path.join(evidenceDir, "stress-measurements.json"),
    `${JSON.stringify({ states }, null, 2)}\n`,
    { encoding: "utf8", mode: 0o600 },
  );
  for (const state of states) {
    expect(state.labels.map((label) => label.suffix)).toEqual(["끝", "Z"]);
    expect(state.labels.every((label) => label.suffixVisible)).toBe(true);
    expect(
      rectanglesIntersect(state.labels[0].labelRect, state.labels[1].labelRect),
    ).toBe(false);
    expect(
      state.labels.every((label) =>
        rectangleContains(label.slotRect, label.labelRect),
      ),
    ).toBe(true);
    expect(state.layout.documentWidth).toBeLessThanOrEqual(
      state.viewport.width,
    );
    expect(
      state.labels.every(
        (label) => label.slotBackground === "rgba(0, 0, 0, 0)",
      ),
    ).toBe(true);
    assertPaintSafety(state.labels, state.pixels);
  }

  const beforeResize = requireTwoLabels(
    await measureLabels(page, 768, darkBoardRgb),
  );
  const resizeHandle = page.locator(".slot-resize").first();
  const handleBox = await resizeHandle.boundingBox();
  if (handleBox === null) throw new Error("Resize handle has no pointer box");
  const resized = page.waitForResponse(
    (response) =>
      response.request().method() === "PATCH" &&
      /\/slots\/[0-9a-f-]+$/u.test(new URL(response.url()).pathname),
  );
  await page.mouse.move(
    handleBox.x + handleBox.width / 2,
    handleBox.y + handleBox.height / 2,
  );
  await page.mouse.down();
  await page.mouse.move(
    handleBox.x + handleBox.width / 2 - 16,
    handleBox.y + handleBox.height / 2,
  );
  await page.mouse.up();
  const resizeResponse = await resized;
  const afterResize = requireTwoLabels(
    await measureLabels(page, 768, darkBoardRgb),
  );
  const resizePixels = await captureSuffixesSequentially(
    page,
    afterResize.length,
    evidenceDir,
    "stress-resized-768",
  );
  assertPaintSafety(afterResize, resizePixels);
  expect(afterResize.every((label) => label.suffixVisible)).toBe(true);
  expect(
    rectanglesIntersect(afterResize[0].labelRect, afterResize[1].labelRect),
  ).toBe(false);
  expect(
    afterResize.every((label) =>
      rectangleContains(label.slotRect, label.labelRect),
    ),
  ).toBe(true);
  expect(
    afterResize.every((label) => label.slotBackground === "rgba(0, 0, 0, 0)"),
  ).toBe(true);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(768);
  expect(
    afterResize[0].slotRect.right - afterResize[0].slotRect.left,
  ).toBeLessThan(
    beforeResize[0].slotRect.right - beforeResize[0].slotRect.left,
  );
  expect(resizeResponse.status()).toBeGreaterThanOrEqual(200);
  expect(resizeResponse.status()).toBeLessThan(300);
  await page.locator(".editor-canvas").screenshot({
    path: path.join(evidenceDir, "stress-canvas-resized-768.png"),
  });
  await page.goto(`/boards/${boardId}/edit`);
  const reopened = requireTwoLabels(
    await measureLabels(page, 768, darkBoardRgb),
  );
  const reopenedPixels = await captureSuffixesSequentially(
    page,
    reopened.length,
    evidenceDir,
    "stress-reopened-768",
  );
  assertPaintSafety(reopened, reopenedPixels);
  expect(reopened.every((label) => label.suffixVisible)).toBe(true);
  expect(
    rectanglesIntersect(reopened[0].labelRect, reopened[1].labelRect),
  ).toBe(false);
  expect(
    reopened.every((label) =>
      rectangleContains(label.slotRect, label.labelRect),
    ),
  ).toBe(true);
  expect(
    reopened.every((label) => label.slotBackground === "rgba(0, 0, 0, 0)"),
  ).toBe(true);
  expect(reopened[0].slotRect.right - reopened[0].slotRect.left).toBeCloseTo(
    afterResize[0].slotRect.right - afterResize[0].slotRect.left,
    1,
  );
  await writeFile(
    path.join(evidenceDir, "resize-measurement.json"),
    `${JSON.stringify({ afterResize, beforeResize, pixels: resizePixels, reopened, reopenedPixels, responseStatus: resizeResponse.status() }, null, 2)}\n`,
    { encoding: "utf8", mode: 0o600 },
  );
});

test("Given one-code-point labels When the responsive canvas renders Then the only glyph remains paint-safe", async ({
  page,
}) => {
  const boardId = await openSyntheticBoard(page, ["A", "끝"]);
  await page.goto(`/boards/${boardId}/edit`);
  const evidenceDir = evidenceDirectory();
  for (const width of [375, 768, 1280]) {
    const labels = requireTwoLabels(await measureLabels(page, width));
    const pixels = await captureSuffixesSequentially(
      page,
      labels.length,
      evidenceDir,
      `boundary-${width}`,
    );
    expect(labels.map((label) => label.fullText)).toEqual(["A", "끝"]);
    assertPaintSafety(labels, pixels);
  }
});

test("Given a capture interruption When controls were hidden Then original control styles are restored", async ({
  page,
}) => {
  await openSyntheticBoard(page);
  const evidenceDir = evidenceDirectory();
  const before = await readControlStyles(page);

  await expect(
    captureSuffixPixels(page, 0, evidenceDir, "forced-interruption", {
      interruptAfterHiddenState: true,
    }),
  ).rejects.toThrow("Forced suffix capture interruption");

  expect(await readControlStyles(page)).toEqual(before);
});
