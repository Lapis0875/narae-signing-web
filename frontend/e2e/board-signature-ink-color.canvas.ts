import type { Locator } from "@playwright/test";

export type PixelSummary = {
  readonly backingHeight: number;
  readonly backingWidth: number;
  readonly black: number;
  readonly clientHeight: number;
  readonly clientWidth: number;
  readonly count: number;
  readonly first: { readonly x: number; readonly y: number } | null;
  readonly firstWhiteAlpha: { readonly x: number; readonly y: number } | null;
  readonly lineWidth: number;
  readonly maxAlpha: number;
  readonly nonTransparent: number;
  readonly opaque: number;
  readonly partialAlpha: number;
  readonly pixelRatio: number;
  readonly targetBounds: {
    readonly maxX: number;
    readonly maxY: number;
    readonly minX: number;
    readonly minY: number;
  } | null;
  readonly rectHeight: number;
  readonly rectWidth: number;
  readonly strokeStyle: string;
  readonly transform: readonly number[];
  readonly transparent: number;
  readonly white: number;
  readonly whiteAlpha: number;
  readonly whiteish: number;
};

export async function opaquePixelCount(
  canvas: Locator,
  rgb: readonly [number, number, number],
): Promise<PixelSummary> {
  return canvas.evaluate((element, target) => {
    const empty = (strokeStyle: string): PixelSummary => ({
      backingHeight: element instanceof HTMLCanvasElement ? element.height : 0,
      backingWidth: element instanceof HTMLCanvasElement ? element.width : 0,
      black: 0,
      clientHeight: element instanceof HTMLElement ? element.clientHeight : 0,
      clientWidth: element instanceof HTMLElement ? element.clientWidth : 0,
      count: 0,
      first: null,
      firstWhiteAlpha: null,
      lineWidth: 0,
      maxAlpha: 0,
      nonTransparent: 0,
      opaque: 0,
      partialAlpha: 0,
      pixelRatio: window.devicePixelRatio,
      rectHeight: element.getBoundingClientRect().height,
      rectWidth: element.getBoundingClientRect().width,
      strokeStyle,
      targetBounds: null,
      transform: [],
      transparent: 0,
      white: 0,
      whiteAlpha: 0,
      whiteish: 0,
    });
    if (!(element instanceof HTMLCanvasElement)) return empty("not-canvas");
    const context = element.getContext("2d");
    if (context === null) return empty("no-context");
    const pixels = context.getImageData(
      0,
      0,
      element.width,
      element.height,
    ).data;
    let count = 0;
    let first: { x: number; y: number } | null = null;
    let firstWhiteAlpha: { x: number; y: number } | null = null;
    let black = 0;
    let maxAlpha = 0;
    let nonTransparent = 0;
    let opaque = 0;
    let partialAlpha = 0;
    let transparent = 0;
    let white = 0;
    let whiteAlpha = 0;
    let whiteish = 0;
    let targetMaxX = -1;
    let targetMaxY = -1;
    let targetMinX = element.width;
    let targetMinY = element.height;
    for (let index = 0; index < pixels.length; index += 4) {
      const red = pixels[index] ?? 0;
      const green = pixels[index + 1] ?? 0;
      const blue = pixels[index + 2] ?? 0;
      const alpha = pixels[index + 3] ?? 0;
      maxAlpha = Math.max(maxAlpha, alpha);
      if (alpha === 0) transparent += 1;
      else if (alpha === 255) opaque += 1;
      else partialAlpha += 1;
      if (alpha > 0) nonTransparent += 1;
      if (red === 0 && green === 0 && blue === 0 && alpha === 255) black += 1;
      if (red === 255 && green === 255 && blue === 255 && alpha > 0) {
        whiteAlpha += 1;
        firstWhiteAlpha ??= {
          x: (index / 4) % element.width,
          y: Math.floor(index / 4 / element.width),
        };
      }
      if (red === 255 && green === 255 && blue === 255 && alpha === 255)
        white += 1;
      if (red >= 240 && green >= 240 && blue >= 240 && alpha > 0) whiteish += 1;
      if (
        red === target[0] &&
        green === target[1] &&
        blue === target[2] &&
        alpha === 255
      ) {
        const x = (index / 4) % element.width;
        const y = Math.floor(index / 4 / element.width);
        count += 1;
        targetMaxX = Math.max(targetMaxX, x);
        targetMaxY = Math.max(targetMaxY, y);
        targetMinX = Math.min(targetMinX, x);
        targetMinY = Math.min(targetMinY, y);
        first ??= {
          x,
          y,
        };
      }
    }
    const rect = element.getBoundingClientRect();
    const transform = context.getTransform();
    return {
      backingHeight: element.height,
      backingWidth: element.width,
      black,
      clientHeight: element.clientHeight,
      clientWidth: element.clientWidth,
      count,
      first,
      firstWhiteAlpha,
      lineWidth: context.lineWidth,
      maxAlpha,
      nonTransparent,
      opaque,
      partialAlpha,
      pixelRatio: window.devicePixelRatio,
      rectHeight: rect.height,
      rectWidth: rect.width,
      strokeStyle: String(context.strokeStyle),
      targetBounds:
        count === 0
          ? null
          : {
              maxX: targetMaxX / element.width,
              maxY: targetMaxY / element.height,
              minX: targetMinX / element.width,
              minY: targetMinY / element.height,
            },
      transform: [
        transform.a,
        transform.b,
        transform.c,
        transform.d,
        transform.e,
        transform.f,
      ],
      transparent,
      white,
      whiteAlpha,
      whiteish,
    };
  }, rgb);
}
