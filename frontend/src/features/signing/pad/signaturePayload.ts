export const MAX_STROKES = 128;
export const MAX_POINTS = 4_096;
export const MAX_PAYLOAD_BYTES = 1_048_576;

const COORDINATE_MAX = 1_000_000;

export type SignaturePoint = {
  readonly x: number;
  readonly y: number;
};

export type SignatureStroke = {
  readonly points: readonly SignaturePoint[];
};

export type SignaturePayload = {
  readonly version: 1;
  readonly strokes: readonly SignatureStroke[];
};

type ClientPoint = {
  readonly clientX: number;
  readonly clientY: number;
};

type Bounds = {
  readonly height: number;
  readonly left: number;
  readonly top: number;
  readonly width: number;
};

function normalizeAxis(value: number, start: number, size: number): number {
  if (size <= 0) {
    return 0;
  }
  const ratio = Math.min(1, Math.max(0, (value - start) / size));
  return Math.floor(ratio * COORDINATE_MAX + 0.5);
}

export function normalizePoint(
  point: ClientPoint,
  bounds: Bounds,
): SignaturePoint {
  return {
    x: normalizeAxis(point.clientX, bounds.left, bounds.width),
    y: normalizeAxis(point.clientY, bounds.top, bounds.height),
  };
}

export function appendPoint(
  points: SignaturePoint[],
  point: SignaturePoint,
): boolean {
  if (points.length >= MAX_POINTS) {
    return false;
  }
  const previous = points.at(-1);
  if (previous?.x === point.x && previous.y === point.y) {
    return false;
  }
  points.push(point);
  return true;
}

function isCoordinate(value: number): boolean {
  return Number.isInteger(value) && value >= 0 && value <= COORDINATE_MAX;
}

export function isSerializedPayloadWithinLimit(serialized: string): boolean {
  return new TextEncoder().encode(serialized).byteLength <= MAX_PAYLOAD_BYTES;
}

export function createPayload(
  strokes: readonly SignatureStroke[],
): SignaturePayload | null {
  if (strokes.length > MAX_STROKES) {
    return null;
  }
  let pointCount = 0;
  for (const stroke of strokes) {
    pointCount += stroke.points.length;
    if (
      stroke.points.length === 0 ||
      pointCount > MAX_POINTS ||
      stroke.points.some(
        (point) => !isCoordinate(point.x) || !isCoordinate(point.y),
      )
    ) {
      return null;
    }
  }
  const payload = { version: 1, strokes } as const;
  return isSerializedPayloadWithinLimit(JSON.stringify(payload))
    ? payload
    : null;
}
