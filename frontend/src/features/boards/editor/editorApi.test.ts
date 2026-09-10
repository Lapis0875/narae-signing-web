import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setCsrfToken } from "../../../api/client.ts";
import { renameBoard, saveSlot } from "./editorApi.ts";

const boardId = "11111111-1111-4111-8111-111111111111";
const slotId = "22222222-2222-4222-8222-222222222222";
const rosterEntryId = "33333333-3333-4333-8333-333333333333";

afterEach(() => {
  setCsrfToken(null);
  vi.restoreAllMocks();
});

describe("saveSlot", () => {
  beforeEach(() => {
    setCsrfToken("csrf-test");
    vi.restoreAllMocks();
  });

  it("Given high-precision drag bounds at a canvas edge When saved Then it sends valid eight-decimal bounds", async () => {
    // Given
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          boardId,
          bounds: { height: 0.2, width: 0.2, x: 0.8, y: 0.12345679 },
          id: slotId,
          revision: 1,
          rosterEntryId,
          signaturePresent: false,
          submitted: false,
        }),
        { headers: { "Content-Type": "application/json" } },
      ),
    );

    // When
    await saveSlot(boardId, slotId, {
      height: 0.200000001,
      width: 0.199999995,
      x: 0.800000005,
      y: 0.123456789,
    });

    // Then
    expect(fetchSpy).toHaveBeenCalledWith(
      `/api/v1/admin/boards/${boardId}/slots/${slotId}`,
      expect.objectContaining({
        body: '{"height":0.2,"width":0.2,"x":0.8,"y":0.12345679}',
      }),
    );
  });

  it("Given placement geometry When saved Then the request and returned geometry stay normalized", async () => {
    // Given
    const normalized = { height: 0.2, width: 0.24, x: 0.76, y: 0.8 };
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          boardId,
          bounds: normalized,
          id: slotId,
          revision: 2,
          rosterEntryId,
          signaturePresent: false,
          submitted: false,
        }),
        { headers: { "Content-Type": "application/json" } },
      ),
    );

    // When
    const result = await saveSlot(boardId, slotId, normalized);

    // Then
    const request = fetchSpy.mock.calls.at(0)?.at(1);
    if (
      request === undefined ||
      typeof request !== "object" ||
      !("body" in request)
    ) {
      throw new TypeError("Expected a request body");
    }
    const body = JSON.parse(String(request.body));
    expect({
      height: body.height,
      width: body.width,
      x: body.x,
      y: body.y,
    }).toEqual(normalized);
    expect(result.bounds).toEqual(normalized);
  });

  it("Given placement geometry When saved Then neither request nor response retains slot background", async () => {
    // Given
    const normalized = { height: 0.2, width: 0.24, x: 0.1, y: 0.2 };
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          boardId,
          bounds: normalized,
          id: slotId,
          revision: 2,
          rosterEntryId,
          signaturePresent: false,
          submitted: false,
        }),
        { headers: { "Content-Type": "application/json" } },
      ),
    );

    // When
    const result = await saveSlot(boardId, slotId, normalized);

    // Then
    const request = fetchSpy.mock.calls.at(0)?.at(1);
    if (
      request === undefined ||
      typeof request !== "object" ||
      !("body" in request)
    ) {
      throw new TypeError("Expected a request body");
    }
    expect(JSON.parse(String(request.body))).toEqual(normalized);
    expect(result).not.toHaveProperty("background");
  });

  it("Given a stale slot background response When saved Then the response is rejected", async () => {
    const normalized = { height: 0.2, width: 0.24, x: 0.1, y: 0.2 };
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          background: "TRANSPARENT",
          boardId,
          bounds: normalized,
          id: slotId,
          revision: 2,
          rosterEntryId,
          signaturePresent: false,
          submitted: false,
        }),
        { headers: { "Content-Type": "application/json" } },
      ),
    );

    await expect(saveSlot(boardId, slotId, normalized)).rejects.toThrow();
  });
});

describe("renameBoard", () => {
  it("Given a future color update When patched Then the helper sends the strict color field", async () => {
    setCsrfToken("csrf-test");
    const board = {
      canvasHeight: 1080,
      canvasWidth: 1920,
      createdAt: "2026-08-21T00:00:00.000Z",
      id: boardId,
      shareLinkVersion: 1,
      signatureInkColor: "white",
      status: "설정 중",
      title: "가을 서명 발표회",
      updatedAt: "2026-08-21T00:00:00.000Z",
    } as const;
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify(board), {
        headers: { "Content-Type": "application/json" },
      }),
    );

    await renameBoard(boardId, { signatureInkColor: "white" });

    const request = fetchSpy.mock.calls.at(0)?.at(1);
    if (
      request === undefined ||
      typeof request !== "object" ||
      !("body" in request)
    ) {
      throw new TypeError("Expected a request body");
    }
    expect(request.body).toBe(JSON.stringify({ signatureInkColor: "white" }));
  });
});
