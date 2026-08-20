import type { Locator } from "@playwright/test";

type ScreenPoint = {
  readonly x: number;
  readonly y: number;
};

type HorizontalStrokeOracle = {
  readonly capShoulder: ScreenPoint;
  readonly center: ScreenPoint;
  readonly edgeCenter: ScreenPoint;
  readonly lineShoulder: ScreenPoint;
  readonly lineWidth: number;
};

type HorizontalStrokeObservation = {
  readonly capShoulderOpaque: boolean;
  readonly centerBlack: boolean;
  readonly edgeCenterBlack: boolean;
  readonly lineShoulderBlack: boolean;
  readonly lineWidth: number;
};

function horizontalStrokeOracle(
  width: number,
  height: number,
): HorizontalStrokeOracle {
  const lineWidth = Math.max(
    1,
    Math.floor(Math.min(width, height) * 0.012 + 0.5),
  );
  const radius = lineWidth / 2;
  const centerY = height / 2;
  const startX = radius + 0.25 * (width - lineWidth);
  return {
    capShoulder: { x: width - 0.25, y: centerY + radius * 0.75 },
    center: { x: (startX + width - radius) / 2, y: centerY },
    edgeCenter: { x: width - 0.25, y: centerY },
    lineShoulder: {
      x: (startX + width - radius) / 2,
      y: centerY + radius - 0.25,
    },
    lineWidth,
  };
}

async function observeHorizontalStroke(
  canvas: Locator,
  oracle: HorizontalStrokeOracle,
): Promise<HorizontalStrokeObservation> {
  return canvas.evaluate((node: HTMLCanvasElement, fixture) => {
    const context = node.getContext("2d");
    if (context === null) {
      return {
        capShoulderOpaque: true,
        centerBlack: false,
        edgeCenterBlack: false,
        lineShoulderBlack: false,
        lineWidth: 0,
      };
    }
    const pixels = context.getImageData(0, 0, node.width, node.height).data;
    const scaleX = node.width / node.clientWidth;
    const scaleY = node.height / node.clientHeight;
    const rgba = (point: ScreenPoint) => {
      const x = Math.min(
        node.width - 1,
        Math.max(0, Math.floor(point.x * scaleX)),
      );
      const y = Math.min(
        node.height - 1,
        Math.max(0, Math.floor(point.y * scaleY)),
      );
      const offset = (y * node.width + x) * 4;
      return {
        alpha: pixels[offset + 3] ?? 0,
        blue: pixels[offset + 2] ?? 255,
        green: pixels[offset + 1] ?? 255,
        red: pixels[offset] ?? 255,
      };
    };
    const isBlack = (point: ScreenPoint) => {
      const pixel = rgba(point);
      return (
        pixel.red === 0 &&
        pixel.green === 0 &&
        pixel.blue === 0 &&
        pixel.alpha > 0
      );
    };
    return {
      capShoulderOpaque: rgba(fixture.capShoulder).alpha > 0,
      centerBlack: isBlack(fixture.center),
      edgeCenterBlack: isBlack(fixture.edgeCenter),
      lineShoulderBlack: isBlack(fixture.lineShoulder),
      lineWidth: context.lineWidth,
    };
  }, oracle);
}

export async function horizontalContractMatches(
  canvas: Locator,
  width: number,
  height: number,
): Promise<boolean> {
  const oracle = horizontalStrokeOracle(width, height);
  const observation = await observeHorizontalStroke(canvas, oracle);
  return (
    observation.lineWidth === oracle.lineWidth &&
    observation.centerBlack &&
    observation.edgeCenterBlack &&
    observation.lineShoulderBlack &&
    !observation.capShoulderOpaque
  );
}
