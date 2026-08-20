import type { SignatureStroke } from "./signaturePayload.ts";

type CanvasSurface = {
  readonly context: CanvasRenderingContext2D;
  readonly height: number;
  readonly width: number;
};

export function calculatePreviewLineWidth(
  slotPixelWidth: number,
  slotPixelHeight: number,
): number {
  return Math.max(
    1,
    Math.floor(Math.min(slotPixelWidth, slotPixelHeight) * 0.012 + 0.5),
  );
}

export function mapPreviewCoordinate(
  coordinate: number,
  size: number,
  lineWidth: number,
): number {
  const inset = Math.min(lineWidth / 2, size / 2);
  return inset + (coordinate / 1_000_000) * (size - inset * 2);
}

export function drawStroke(
  surface: CanvasSurface,
  stroke: SignatureStroke,
): void {
  const first = stroke.points[0];
  if (first === undefined) {
    return;
  }
  const toCanvasX = (value: number) =>
    mapPreviewCoordinate(value, surface.width, surface.context.lineWidth);
  const toCanvasY = (value: number) =>
    mapPreviewCoordinate(value, surface.height, surface.context.lineWidth);

  surface.context.beginPath();
  surface.context.moveTo(toCanvasX(first.x), toCanvasY(first.y));
  if (stroke.points.length === 1) {
    surface.context.lineTo(
      toCanvasX(first.x) + 0.01,
      toCanvasY(first.y) + 0.01,
    );
  } else {
    for (const point of stroke.points.slice(1)) {
      surface.context.lineTo(toCanvasX(point.x), toCanvasY(point.y));
    }
  }
  surface.context.stroke();
}
