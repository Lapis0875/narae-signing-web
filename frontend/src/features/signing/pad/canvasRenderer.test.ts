import { describe, expect, it } from "vitest";
import {
  calculatePreviewLineWidth,
  mapPreviewCoordinate,
} from "./canvasRenderer.ts";

describe("signature preview geometry", () => {
  it("uses HALF_UP 1.2% width with a one-pixel floor", () => {
    expect(calculatePreviewLineWidth(20, 40)).toBe(1);
    expect(calculatePreviewLineWidth(400, 200)).toBe(2);
    expect(calculatePreviewLineWidth(300, 300)).toBe(4);
  });

  it("insets normalized edges by the round-cap radius", () => {
    expect(mapPreviewCoordinate(0, 200, 4)).toBe(2);
    expect(mapPreviewCoordinate(500_000, 200, 4)).toBe(100);
    expect(mapPreviewCoordinate(1_000_000, 200, 4)).toBe(198);
  });
});
