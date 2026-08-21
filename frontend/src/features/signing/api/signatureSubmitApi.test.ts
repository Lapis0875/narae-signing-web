import { describe, expect, it, vi } from "vitest";
import type { SignatureStroke } from "../pad/signaturePayload.ts";
import {
  SignaturePayloadError,
  submitSignature,
} from "./signatureSubmitApi.ts";

describe("signature submit API", () => {
  it("sends the exact version-one payload once", async () => {
    // Given
    const strokes: readonly SignatureStroke[] = [
      { points: [{ x: 0, y: 1_000_000 }] },
    ];
    const transport = vi.fn().mockResolvedValue({ submitted: true });

    // When
    const result = await submitSignature(strokes, transport);

    // Then
    expect(result).toEqual({ submitted: true });
    expect(transport).toHaveBeenCalledOnce();
    expect(transport).toHaveBeenCalledWith(
      "/api/v1/public/signing-session/signature",
      {
        body: JSON.stringify({ version: 1, strokes }),
        headers: { "Content-Type": "application/json" },
        method: "POST",
      },
    );
  });

  it("retains caller strokes and never retries a transport failure", async () => {
    // Given
    const strokes: readonly SignatureStroke[] = [
      { points: [{ x: 12, y: 34 }] },
    ];
    const snapshot = structuredClone(strokes);
    const transport = vi
      .fn()
      .mockRejectedValue(new TypeError("network failed"));

    // When
    const request = submitSignature(strokes, transport);

    // Then
    await expect(request).rejects.toThrow("network failed");
    expect(transport).toHaveBeenCalledOnce();
    expect(strokes).toEqual(snapshot);
  });

  it("rejects capped input before allocating transport work", async () => {
    // Given
    const strokes = Array.from({ length: 129 }, () => ({
      points: [{ x: 1, y: 1 }],
    }));
    const transport = vi.fn();

    // When
    const request = submitSignature(strokes, transport);

    // Then
    await expect(request).rejects.toBeInstanceOf(SignaturePayloadError);
    expect(transport).not.toHaveBeenCalled();
    expect(strokes).toHaveLength(129);
  });
});
