import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import type { Page } from "@playwright/test";

export type BrowserEvent = {
  readonly contentType?: string;
  readonly kind: "console" | "pageerror" | "requestfailed" | "response";
  readonly level?: "error" | "warning";
  readonly message?: string;
  readonly path?: string;
  readonly status?: number;
};

export type VisualState = {
  readonly browserEvents: readonly BrowserEvent[];
  readonly capturedAt: string;
  readonly captureMode: "rest" | "scroll";
  readonly captureDurationMs: number;
  readonly clip: {
    readonly height: number;
    readonly width: number;
    readonly x: number;
    readonly y: number;
  } | null;
  readonly images: readonly {
    readonly className: string;
    readonly complete: boolean;
    readonly naturalHeight: number;
    readonly naturalWidth: number;
    readonly rect: {
      readonly height: number;
      readonly width: number;
      readonly x: number;
      readonly y: number;
    };
    readonly src: string;
  }[];
  readonly label: string;
  readonly png: {
    readonly darkPixels: number;
    readonly height: number;
    readonly magentaPixels: number;
    readonly whitePixels: number;
    readonly width: number;
  };
  readonly privacy: {
    readonly sensitiveNodes: number;
    readonly sensitiveNodesInCapture: number;
  };
  readonly screenshot: string;
  readonly sha256: string;
  readonly slotLabels: readonly string[];
  readonly scrollY: number;
  readonly viewport: {
    readonly height: number;
    readonly width: number;
  };
};

function sanitized(value: string): string {
  return value
    .replace(/blob:https?:\/\/[^\s'"]+/gu, "blob:<redacted>")
    .replace(
      /\/api\/v1\/public\/links\/[A-Za-z0-9_-]+/gu,
      "/api/v1/public/links/<redacted>",
    )
    .replace(/\/display\/[A-Za-z0-9_-]{20,}/gu, "/display/<redacted>")
    .replace(/\/sign\/[A-Za-z0-9_-]{20,}/gu, "/sign/<redacted>");
}

export function observeVisualPage(page: Page): BrowserEvent[] {
  const events: BrowserEvent[] = [];
  page.on("console", (message) => {
    if (message.type() === "error" || message.type() === "warning")
      events.push({
        kind: "console",
        level: message.type(),
        message: sanitized(message.text()),
      });
  });
  page.on("pageerror", (error) =>
    events.push({ kind: "pageerror", message: sanitized(error.message) }),
  );
  page.on("requestfailed", (request) => {
    const url = new URL(request.url());
    events.push({
      kind: "requestfailed",
      message: sanitized(request.failure()?.errorText ?? "unknown"),
      path: request.url().startsWith("blob:")
        ? "blob:<redacted>"
        : sanitized(url.pathname),
    });
  });
  page.on("response", (response) => {
    const url = new URL(response.url());
    if (!url.pathname.endsWith("/background") && response.status() < 400)
      return;
    events.push({
      contentType: response.headers()["content-type"],
      kind: "response",
      path: sanitized(url.pathname),
      status: response.status(),
    });
  });
  return events;
}

export async function captureVisualState(
  page: Page,
  evidenceDir: string,
  label: string,
  browserEvents: readonly BrowserEvent[],
  viewport: { readonly height: number; readonly width: number },
  captureMode: "rest" | "scroll" = "rest",
): Promise<VisualState> {
  const captureStartedAt = Date.now();
  await page.setViewportSize(viewport);
  if (captureMode === "rest") await page.evaluate(() => window.scrollTo(0, 0));
  const scrollY = await page.evaluate(() => window.scrollY);
  const screenshot = `${label}-${captureMode}-${viewport.width}.png`;
  const screenshotPath = `${evidenceDir}/${screenshot}`;
  const clip =
    label.startsWith("admin-") && viewport.width === 1280
      ? { height: viewport.height, width: 840, x: 0, y: 0 }
      : null;
  const privacy = await page.evaluate(
    (capture) => {
      const sensitiveNodes = [
        ...document.querySelectorAll(
          '.editor-share-url, img[alt="현재 서명 링크 QR"]',
        ),
      ];
      const captureRect = {
        bottom: capture.y + capture.height,
        left: capture.x,
        right: capture.x + capture.width,
        top: capture.y,
      };
      const sensitiveNodesInCapture = sensitiveNodes.filter((node) => {
        const rect = node.getBoundingClientRect();
        const viewportRect = {
          bottom: rect.bottom,
          left: rect.left,
          right: rect.right,
          top: rect.top,
        };
        return !(
          viewportRect.right <= captureRect.left ||
          viewportRect.left >= captureRect.right ||
          viewportRect.bottom <= captureRect.top ||
          viewportRect.top >= captureRect.bottom
        );
      }).length;
      return { sensitiveNodes: sensitiveNodes.length, sensitiveNodesInCapture };
    },
    clip ?? {
      height: viewport.height,
      width: viewport.width,
      x: 0,
      y: 0,
    },
  );
  if (privacy.sensitiveNodesInCapture !== 0)
    throw new Error("Task 8 visual capture intersects share URL or QR");
  await page.screenshot({
    clip: clip ?? undefined,
    fullPage: false,
    path: screenshotPath,
  });
  const images = await page.locator("img").evaluateAll((nodes) =>
    nodes.map((node) => {
      const rect = node.getBoundingClientRect();
      const rawSource = node.currentSrc || node.src;
      const source = rawSource.startsWith("blob:")
        ? "blob:<redacted>"
        : new URL(rawSource, location.href).pathname.replace(
            /\/api\/v1\/public\/links\/[A-Za-z0-9_-]+/gu,
            "/api/v1/public/links/<redacted>",
          );
      return {
        className: node.className,
        complete: node.complete,
        naturalHeight: node.naturalHeight,
        naturalWidth: node.naturalWidth,
        rect: { height: rect.height, width: rect.width, x: rect.x, y: rect.y },
        src: source,
      };
    }),
  );
  const slotLabels = await page.locator(".slot-label").allTextContents();
  const screenshotBytes = await readFile(screenshotPath);
  const encoded = screenshotBytes.toString("base64");
  const png = await page.evaluate(async (base64) => {
    const bytes = Uint8Array.from(atob(base64), (value) => value.charCodeAt(0));
    const bitmap = await createImageBitmap(
      new Blob([bytes], { type: "image/png" }),
    );
    const canvas = document.createElement("canvas");
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext("2d");
    if (context === null) throw new Error("Task 8 visual PNG context missing");
    context.drawImage(bitmap, 0, 0);
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    let darkPixels = 0;
    let magentaPixels = 0;
    let whitePixels = 0;
    for (let index = 0; index < pixels.length; index += 4) {
      const red = pixels[index] ?? 0;
      const green = pixels[index + 1] ?? 0;
      const blue = pixels[index + 2] ?? 0;
      if (red === 32 && green === 32 && blue === 32) darkPixels += 1;
      if (red === 255 && green === 0 && blue === 255) magentaPixels += 1;
      if (red === 255 && green === 255 && blue === 255) whitePixels += 1;
    }
    return {
      darkPixels,
      height: canvas.height,
      magentaPixels,
      whitePixels,
      width: canvas.width,
    };
  }, encoded);
  return {
    browserEvents: [...browserEvents],
    capturedAt: new Date().toISOString(),
    captureMode,
    captureDurationMs: Date.now() - captureStartedAt,
    clip,
    images,
    label,
    png,
    privacy,
    screenshot,
    sha256: createHash("sha256").update(screenshotBytes).digest("hex"),
    slotLabels,
    scrollY,
    viewport,
  };
}
