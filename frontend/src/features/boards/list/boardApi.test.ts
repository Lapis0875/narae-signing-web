import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setCsrfToken } from "../../../api/client.ts";
import { boardSchema, createBoard } from "./boardApi.ts";

const board = {
  canvasHeight: 1080,
  canvasWidth: 1920,
  createdAt: "2026-08-21T00:00:00.000Z",
  id: "11111111-1111-4111-8111-111111111111",
  shareLinkVersion: 1,
  signatureInkColor: "black",
  status: "설정 중",
  title: "가을 서명 발표회",
  updatedAt: "2026-08-21T00:00:00.000Z",
} as const;

beforeEach(() => setCsrfToken("csrf-test"));
afterEach(() => {
  setCsrfToken(null);
  vi.restoreAllMocks();
});

describe("board API contract", () => {
  it.each(["black", "white"] as const)(
    "Given %s ink When a board is parsed Then the required color is retained",
    (signatureInkColor) => {
      expect(
        boardSchema.parse({ ...board, signatureInkColor }).signatureInkColor,
      ).toBe(signatureInkColor);
    },
  );

  it.each([
    ["missing", undefined],
    ["null", null],
    ["unknown", "blue"],
    ["case variant", "BLACK"],
  ])(
    "Given %s ink When a board is parsed Then the payload is rejected",
    (_label, signatureInkColor) => {
      const payload = { ...board, signatureInkColor };
      if (signatureInkColor === undefined)
        Reflect.deleteProperty(payload, "signatureInkColor");
      expect(() => boardSchema.parse(payload)).toThrow();
    },
  );

  it("Given a title When a board is created Then the request body remains title-only", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          board,
          shareToken: "safe-share",
        }),
        { headers: { "Content-Type": "application/json" } },
      ),
    );

    await createBoard(board.title);

    const request = fetchSpy.mock.calls.at(0)?.at(1);
    if (
      request === undefined ||
      typeof request !== "object" ||
      !("body" in request)
    ) {
      throw new TypeError("Expected a request body");
    }
    expect(request.body).toBe(JSON.stringify({ title: board.title }));
  });
});
