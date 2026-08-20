import { describe, expect, it } from "vitest";
import {
  appendPoint,
  createPayload,
  isSerializedPayloadWithinLimit,
  MAX_PAYLOAD_BYTES,
  MAX_POINTS,
  MAX_STROKES,
  normalizePoint,
} from "./signaturePayload.ts";

describe("signature payload", () => {
  it("maps canvas coordinates with HALF_UP rounding", () => {
    const bounds = { height: 200, left: 10, top: 20, width: 400 };

    expect(normalizePoint({ clientX: 10, clientY: 20 }, bounds)).toEqual({
      x: 0,
      y: 0,
    });
    expect(
      normalizePoint({ clientX: 210.0002, clientY: 120.0001 }, bounds),
    ).toEqual({
      x: 500_001,
      y: 500_001,
    });
    expect(normalizePoint({ clientX: 410, clientY: 220 }, bounds)).toEqual({
      x: 1_000_000,
      y: 1_000_000,
    });
  });

  it("clamps out-of-bounds input and removes consecutive duplicates", () => {
    const points = [{ x: 0, y: 0 }];

    expect(
      appendPoint(
        points,
        normalizePoint(
          { clientX: -20, clientY: -20 },
          {
            height: 100,
            left: 0,
            top: 0,
            width: 100,
          },
        ),
      ),
    ).toBe(false);
    expect(points).toEqual([{ x: 0, y: 0 }]);
    expect(
      normalizePoint(
        { clientX: -20, clientY: 300 },
        {
          height: 100,
          left: 0,
          top: 0,
          width: 100,
        },
      ),
    ).toEqual({ x: 0, y: 1_000_000 });
  });

  it("keeps exact wire shape and rejects data beyond hard caps", () => {
    const cappedPoints = Array.from({ length: MAX_POINTS }, (_, index) => ({
      x: index,
      y: index,
    }));
    const strokes = Array.from({ length: MAX_STROKES }, () => ({
      points: [{ x: 1, y: 1 }],
    }));

    expect(appendPoint(cappedPoints, { x: MAX_POINTS, y: MAX_POINTS })).toBe(
      false,
    );
    expect(
      createPayload([
        { points: cappedPoints },
        { points: [{ x: 1_000_000, y: 1_000_000 }] },
      ]),
    ).toBeNull();
    expect(isSerializedPayloadWithinLimit("x".repeat(MAX_PAYLOAD_BYTES))).toBe(
      true,
    );
    expect(
      isSerializedPayloadWithinLimit(
        `{"data":"${"한".repeat(MAX_PAYLOAD_BYTES)}"}`,
      ),
    ).toBe(false);
    expect(createPayload(strokes)).toEqual({ version: 1, strokes });
    expect(
      createPayload([...strokes, { points: [{ x: 2, y: 2 }] }]),
    ).toBeNull();
  });
});
