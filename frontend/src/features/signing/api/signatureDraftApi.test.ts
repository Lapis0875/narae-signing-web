import { afterEach, expect, it, vi } from "vitest"
import { setCsrfToken } from "../../../api/client.ts"
import {
  appendSignatureDraft,
  cancelSignatureDraft,
  clearSignatureDraft,
  updateSignatureDraft,
} from "./signatureDraftApi.ts"

afterEach(() => {
  setCsrfToken(null)
  vi.restoreAllMocks()
})

it("sends full and delta drafts through their canonical signer session APIs", async () => {
  const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) =>
    input.toString().endsWith("/clear") || input.toString().endsWith("/cancel")
      ? new Response(null, { status: 204 })
      : new Response(
        JSON.stringify({ draftEpoch: 3, revision: 4 }),
        { headers: { "Content-Type": "application/json" } },
      ))
  vi.spyOn(document, "cookie", "get").mockReturnValue("XSRF-TOKEN=draft-csrf-token")
  setCsrfToken("draft-csrf-token")

  await updateSignatureDraft({
    strokes: [{ points: [{ x: 10, y: 20 }] }],
    version: 1,
  })
  await appendSignatureDraft({
    clientSequence: 5,
    draftEpoch: 3,
    operation: "append",
    points: [{ x: 30, y: 40 }],
    revision: 4,
    strokeIndex: 0,
  })
  await clearSignatureDraft()
  await cancelSignatureDraft()

  expect(fetchMock).toHaveBeenNthCalledWith(1, "/api/v1/public/signing-session/draft", expect.objectContaining({
    body: "{\"strokes\":[{\"points\":[{\"x\":10,\"y\":20}]}],\"version\":1}",
    method: "PUT",
  }))
  expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/v1/public/signing-session/draft/delta", expect.objectContaining({
    body: "{\"operation\":\"append\",\"clientSequence\":5,\"draftEpoch\":3,\"revision\":4,\"strokeIndex\":0,\"points\":[{\"x\":30,\"y\":40}]}",
    method: "POST",
  }))
  expect(fetchMock).toHaveBeenNthCalledWith(3, "/api/v1/public/signing-session/draft/clear", expect.objectContaining({
    method: "POST",
  }))
  expect(fetchMock).toHaveBeenNthCalledWith(4, "/api/v1/public/signing-session/cancel", expect.objectContaining({
    method: "POST",
  }))
})
