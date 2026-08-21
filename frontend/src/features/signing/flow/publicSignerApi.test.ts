import { afterEach, describe, expect, it, vi } from "vitest"
import { readSigningSession } from "./publicSignerApi.ts"

describe("public signer session response", () => {
  afterEach(() => vi.restoreAllMocks())

  it("preserves the optional server-issued signature aspect ratio", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      signatureAspectRatio: 1.777778,
      state: "READY",
    }), { headers: { "Content-Type": "application/json" } }))

    await expect(readSigningSession()).resolves.toEqual({
      signatureAspectRatio: 1.777778,
      state: "READY",
    })
  })
})
