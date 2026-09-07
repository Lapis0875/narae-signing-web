import { chromium, expect, test } from "@playwright/test";
import {
  captureErrorEvidence,
  captureResponsiveEvidence,
  dispatchPenStroke,
  dispatchTouchStroke,
  signatureCanvasBox,
} from "../src/features/signing/pad/e2eDriver.ts";
import { horizontalContractMatches } from "../src/features/signing/pad/e2eOracle.ts";
import "./signatureDraftRecovery.ts";

test("tablet pointer signature pad preserves deterministic bounded input", async ({
  browserName,
}, testInfo) => {
  test.skip(browserName !== "chromium", "Retail Chrome evidence");
  const browser = await chromium.launch({ channel: "chrome" });
  const context = await browser.newContext({
    baseURL: testInfo.project.use.baseURL,
    deviceScaleFactor: 2,
    viewport: { height: 720, width: 900 },
  });
  const page = await context.newPage();
  const cdp = await context.newCDPSession(page);

  try {
    await page.goto("/e2e/harness/signature-pad.html");
    const canvas = page.getByTestId("signer-canvas");
    await expect(canvas).toBeVisible();
    await expect(canvas).toHaveCSS("touch-action", "none");
    expect(
      await canvas.evaluate((node) => {
        const style = getComputedStyle(node);
        return {
          background: style.backgroundColor,
          ink: style.getPropertyValue("--signature-pad-ink").trim(),
          paper: style.getPropertyValue("--color-paper").trim(),
        };
      }),
    ).toEqual({
      background: "rgb(255, 255, 255)",
      ink: "#000000",
      paper: "#fff",
    });
    await expect(
      canvas.locator("xpath=.."),
      "component semantic panel",
    ).toHaveClass(/app-panel route-panel signature-pad/);
    await expect(page.getByRole("button", { name: "모두 지우기" })).toHaveClass(
      /signature-pad__action/,
    );
    expect(
      await captureResponsiveEvidence(page, cdp, {
        mobile: testInfo.outputPath("task-18-mobile-375.png"),
        tablet: testInfo.outputPath("task-18-tablet-768.png"),
      }),
    ).toBe(true);
    await expect(canvas).toHaveAttribute("data-stroke-count", "1");
    await page.getByRole("button", { name: "모두 지우기" }).click();

    await page.setViewportSize({ height: 720, width: 900 });
    const initialBox = await signatureCanvasBox(page);
    const initialClientWidth = await canvas.evaluate(
      (node) => node.clientWidth,
    );
    await expect
      .poll(() => canvas.getAttribute("width"))
      .toBe(String(Math.round(initialClientWidth * 2)));

    const penPoints = [
      {
        x: initialBox.x + initialBox.width * 0.25,
        y: initialBox.y + initialBox.height * 0.25,
      },
      {
        x: initialBox.x + initialBox.width * 0.5,
        y: initialBox.y + initialBox.height * 0.5,
      },
      {
        x: initialBox.x + initialBox.width + 50,
        y: initialBox.y + initialBox.height + 50,
      },
    ];
    await dispatchPenStroke(cdp, penPoints);
    await expect(canvas).toHaveAttribute("data-stroke-count", "1");

    await page.getByRole("button", { name: "제출 거부 꺼짐" }).click();
    await page.getByRole("button", { name: "서명 제출" }).click();
    await expect(page.getByTestId("signature-status")).toContainText(
      "저장하지 못했습니다",
    );
    await expect(canvas).toHaveAttribute("data-stroke-count", "1");
    await expect(page.getByTestId("payload-summary")).toHaveText(
      "v=1;strokes=1;points=3;checksum=84000000;matching=0",
    );

    await dispatchTouchStroke(cdp, penPoints);
    await expect(canvas).toHaveAttribute("data-stroke-count", "2");
    await page.getByRole("button", { name: "서명 제출" }).click();
    await expect(page.getByTestId("payload-summary")).toHaveText(
      "v=1;strokes=2;points=6;checksum=168000000;matching=1",
    );
    await expect(page.locator("body")).not.toContainText('"strokes"');
    await expect(page.locator("pre")).toHaveCount(0);

    await page.mouse.move(
      initialBox.x + initialBox.width * 0.2,
      initialBox.y + initialBox.height * 0.8,
    );
    await page.mouse.down();
    await page.mouse.move(
      initialBox.x + initialBox.width + 30,
      initialBox.y + initialBox.height + 30,
    );
    await page.mouse.up();
    await expect(canvas).toHaveAttribute("data-stroke-count", "3");

    await dispatchTouchStroke(cdp, penPoints, true);
    await expect(canvas).toHaveAttribute("data-stroke-count", "3");
    await dispatchTouchStroke(cdp, penPoints);
    await expect(canvas).toHaveAttribute("data-stroke-count", "4");

    await page.setViewportSize({ height: 720, width: 760 });
    const resizedBox = await signatureCanvasBox(page);
    const resizedClientSize = await canvas.evaluate((node) => ({
      height: node.clientHeight,
      width: node.clientWidth,
    }));
    await expect
      .poll(() => canvas.getAttribute("width"))
      .toBe(String(Math.round(resizedClientSize.width * 2)));
    const blackPixelCount = await canvas.evaluate((node) => {
      const context = node.getContext("2d");
      if (context === null) {
        return 0;
      }
      const pixels = context.getImageData(0, 0, node.width, node.height).data;
      let count = 0;
      for (let index = 0; index < pixels.length; index += 4) {
        if (
          pixels[index] === 0 &&
          pixels[index + 1] === 0 &&
          pixels[index + 2] === 0 &&
          (pixels[index + 3] ?? 0) > 0
        ) {
          count += 1;
        }
      }
      return count;
    });
    expect(blackPixelCount).toBeGreaterThan(100);
    expect(
      await canvas.evaluate((node) => {
        const context = node.getContext("2d");
        return context === null
          ? null
          : {
              lineCap: context.lineCap,
              lineJoin: context.lineJoin,
              strokeStyle: context.strokeStyle,
            };
      }),
    ).toEqual({
      lineCap: "round",
      lineJoin: "round",
      strokeStyle: "#000000",
    });
    await page.getByRole("button", { name: "모두 지우기" }).click();
    await dispatchPenStroke(cdp, [
      {
        x: resizedBox.x + resizedBox.width * 0.25,
        y: resizedBox.y + resizedBox.height * 0.5,
      },
      {
        x: resizedBox.x + resizedBox.width + 20,
        y: resizedBox.y + resizedBox.height * 0.5,
      },
    ]);
    await page.getByRole("button", { name: "서명 제출" }).click();
    await expect(canvas).toHaveAttribute("data-stroke-count", "1");
    await expect(page.getByTestId("signature-status")).toContainText(
      "저장하지 못했습니다",
    );
    await expect(page.getByTestId("payload-summary")).toHaveText(
      "v=1;strokes=1;points=2;checksum=55750000;matching=0",
    );
    expect(
      await horizontalContractMatches(
        canvas,
        resizedClientSize.width,
        resizedClientSize.height,
      ),
    ).toBe(true);
    await page.getByRole("main").click({ position: { x: 5, y: 5 } });
    await page.keyboard.press("Tab");
    await page.keyboard.press("Tab");
    await page.keyboard.press("Tab");
    await expect(page.getByRole("button", { name: "서명 제출" })).toBeFocused();
    await expect(page.getByRole("button", { name: "서명 제출" })).toHaveCSS(
      "outline-style",
      "solid",
    );
    await page.screenshot({
      path: testInfo.outputPath("task-18-signature-pad.png"),
    });

    for (let index = 0; index < 130; index += 1) {
      const x = resizedBox.x + 4 + (index % 100);
      const y = resizedBox.y + 4 + (index % 200);
      await dispatchPenStroke(cdp, [
        { x, y },
        { x: x + 1, y: y + 1 },
      ]);
    }
    await expect(canvas).toHaveAttribute("data-stroke-count", "128");
    await expect(page.getByTestId("signature-status")).toContainText(
      "허용 범위를 초과했습니다",
    );
    expect(
      await captureErrorEvidence(page, {
        desktop: testInfo.outputPath("task-18-signature-pad-error.png"),
        mobile: testInfo.outputPath("task-18-error-375.png"),
        tablet: testInfo.outputPath("task-18-error-768.png"),
      }),
    ).toBe(true);

    await page.getByRole("button", { name: "제출 거부 켜짐" }).click();
    await page.getByRole("button", { name: "서명 제출" }).click();
    await expect(page.getByTestId("signature-status")).toContainText(
      "저장되었습니다",
    );
    await expect(canvas).toHaveAttribute("data-stroke-count", "0");
    await page.screenshot({
      path: testInfo.outputPath("task-18-success-clear.png"),
    });

    await dispatchPenStroke(cdp, [
      { x: resizedBox.x + 20, y: resizedBox.y + 20 },
      { x: resizedBox.x + 40, y: resizedBox.y + 40 },
    ]);
    await expect(canvas).toHaveAttribute("data-stroke-count", "1");
    await page.getByRole("button", { name: "모두 지우기" }).click();
    await expect(canvas).toHaveAttribute("data-stroke-count", "0");
    await expect(page.getByTestId("signature-status")).toHaveText(
      "서명을 모두 지웠습니다.",
    );
    await page.screenshot({
      path: testInfo.outputPath("task-18-user-clear.png"),
    });
    await page.setViewportSize({ height: 812, width: 375 });
  } finally {
    await context.close();
    await browser.close();
  }
});
