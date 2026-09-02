import { afterEach, expect, it, vi } from "vitest"
import { setCsrfToken } from "../../../api/client.ts"
import { cancelSignatureDraft, clearSignatureDraft, updateSignatureDraft } from "./signatureDraftApi.ts"

afterEach(() => {
  setCsrfToken(null)
  vi.restoreAllMocks()
})

it("sends the full current draft and cancellation through the signer session API", async () => {
  const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 204 }))
  vi.spyOn(document, "cookie", "get").mockReturnValue("XSRF-TOKEN=draft-csrf-token")
  setCsrfToken("draft-csrf-token")

  await updateSignatureDraft({
    strokes: [{ points: [{ x: 10, y: 20 }] }],
    version: 1,
  })
  await clearSignatureDraft()
  await cancelSignatureDraft()

  expect(fetchMock).toHaveBeenNthCalledWith(1, "/api/v1/public/signing-session/draft", expect.objectContaining({
    body: "{\"strokes\":[{\"points\":[{\"x\":10,\"y\":20}]}],\"version\":1}",
    method: "PUT",
  }))
  expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/v1/public/signing-session/draft/clear", expect.objectContaining({
    method: "POST",
  }))
  expect(fetchMock).toHaveBeenNthCalledWith(3, "/api/v1/public/signing-session/cancel", expect.objectContaining({
    method: "POST",
  }))
})
