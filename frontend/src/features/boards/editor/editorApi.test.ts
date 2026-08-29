import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { setCsrfToken } from "../../../api/client.ts"
import { saveSlot } from "./editorApi.ts"

const boardId = "11111111-1111-4111-8111-111111111111"
const slotId = "22222222-2222-4222-8222-222222222222"
const rosterEntryId = "33333333-3333-4333-8333-333333333333"

describe("saveSlot", () => {
  beforeEach(() => {
    setCsrfToken("csrf-test")
    vi.restoreAllMocks()
  })

  afterEach(() => setCsrfToken(null))

  it("Given high-precision drag bounds at a canvas edge When saved Then it sends valid eight-decimal bounds", async () => {
    // Given
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      background: "TRANSPARENT",
      boardId,
      bounds: { height: 0.2, width: 0.2, x: 0.8, y: 0.12345679 },
      id: slotId,
      revision: 1,
      rosterEntryId,
      signaturePresent: false,
      submitted: false,
    }), { headers: { "Content-Type": "application/json" } }))

    // When
    await saveSlot(boardId, slotId, {
      height: 0.200000001,
      width: 0.199999995,
      x: 0.800000005,
      y: 0.123456789,
    }, "transparent")

    // Then
    expect(fetchSpy).toHaveBeenCalledWith(
      `/api/v1/admin/boards/${boardId}/slots/${slotId}`,
      expect.objectContaining({
        body: "{\"background\":\"transparent\",\"height\":0.2,\"width\":0.2,\"x\":0.8,\"y\":0.12345679}",
      }),
    )
  })
})
