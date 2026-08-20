import type { CDPSession, Locator, Page } from "@playwright/test";

type ScreenPoint = {
  readonly x: number;
  readonly y: number;
};

type ResponsiveEvidencePaths = {
  readonly mobile: string;
  readonly tablet: string;
};

type ErrorEvidencePaths = {
  readonly desktop: string;
  readonly mobile: string;
  readonly tablet: string;
};

export async function dispatchPenStroke(
  cdp: CDPSession,
  points: readonly ScreenPoint[],
): Promise<void> {
  const first = points[0];
  const last = points.at(-1);
  if (first === undefined || last === undefined) {
    throw new Error("Pen stroke needs at least one point");
  }
  await cdp.send("Input.dispatchMouseEvent", {
    button: "left",
    buttons: 1,
    clickCount: 1,
    pointerType: "pen",
    type: "mousePressed",
    ...first,
  });
  for (const point of points.slice(1, -1)) {
    await cdp.send("Input.dispatchMouseEvent", {
      buttons: 1,
      pointerType: "pen",
      type: "mouseMoved",
      ...point,
    });
  }
  await cdp.send("Input.dispatchMouseEvent", {
    button: "left",
    buttons: 0,
    clickCount: 1,
    pointerType: "pen",
    type: "mouseReleased",
    ...last,
  });
}

export async function dispatchTouchStroke(
  cdp: CDPSession,
  points: readonly ScreenPoint[],
  cancel = false,
): Promise<void> {
  const first = points[0];
  const last = points.at(-1);
  if (first === undefined || last === undefined) {
    throw new Error("Touch stroke needs at least one point");
  }
  await cdp.send("Input.dispatchTouchEvent", {
    touchPoints: [first],
    type: "touchStart",
  });
  for (const point of points.slice(1)) {
    await cdp.send("Input.dispatchTouchEvent", {
      touchPoints: [point],
      type: "touchMove",
    });
  }
  await cdp.send("Input.dispatchTouchEvent", {
    touchPoints: [],
    type: cancel ? "touchCancel" : "touchEnd",
  });
}

export async function signatureCanvasBox(page: Page) {
  const box = await page.getByRole("img", { name: "서명 패드" }).boundingBox();
  if (box === null) {
    throw new Error("Signature pad canvas is not visible");
  }
  return box;
}

export async function captureResponsiveEvidence(
  page: Page,
  cdp: CDPSession,
  paths: ResponsiveEvidencePaths,
): Promise<boolean> {
  await page.setViewportSize({ height: 812, width: 375 });
  const controlsFit = await page.getByRole("button").evaluateAll((buttons) =>
    buttons.every((button) => {
      const rect = button.getBoundingClientRect();
      return (
        button.scrollWidth <= button.clientWidth &&
        rect.left >= 0 &&
        rect.right <= innerWidth
      );
    }),
  );
  await page.screenshot({ path: paths.mobile });

  await page.setViewportSize({ height: 1024, width: 768 });
  const tabletBox = await signatureCanvasBox(page);
  await dispatchPenStroke(cdp, [
    { x: tabletBox.x + 20, y: tabletBox.y + 20 },
    { x: tabletBox.x + 80, y: tabletBox.y + 80 },
  ]);
  await page.screenshot({ path: paths.tablet });
  return controlsFit;
}

export async function captureErrorEvidence(
  page: Page,
  paths: ErrorEvidencePaths,
): Promise<boolean> {
  const checkpoints = [
    { height: 720, path: paths.desktop, width: 760 },
    { height: 1024, path: paths.tablet, width: 768 },
    { height: 812, path: paths.mobile, width: 375 },
  ] as const;
  const fits: boolean[] = [];
  for (const checkpoint of checkpoints) {
    await page.setViewportSize(checkpoint);
    await waitForStablePaint(page);
    fits.push(
      await renderedTextFits(page.getByTestId("signature-status")),
      await renderedTextFits(page.getByRole("button", { name: "모두 지우기" })),
      await renderedTextFits(page.getByRole("button", { name: "서명 제출" })),
    );
    await page.screenshot({ path: checkpoint.path });
  }
  await page.setViewportSize({ height: 720, width: 760 });
  return fits.every(Boolean);
}

export async function renderedTextFits(locator: Locator): Promise<boolean> {
  return locator.evaluate((element) => {
    const text = element.textContent?.trim() ?? "";
    const elementRect = element.getBoundingClientRect();
    const range = document.createRange();
    range.selectNodeContents(element);
    const textRect = range.getBoundingClientRect();
    return (
      text.length > 0 &&
      textRect.width > 0 &&
      textRect.left >= elementRect.left - 0.5 &&
      textRect.right <= elementRect.right + 0.5 &&
      textRect.left >= 0 &&
      textRect.right <= innerWidth
    );
  });
}

export async function waitForStablePaint(page: Page): Promise<void> {
  await page.evaluate(
    () =>
      new Promise<void>((resolve) => {
        requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
      }),
  );
}
