import { afterEach, describe, expect, it, vi } from "vitest"
import { fetchCurrentBackground } from "./editorApi.ts"

const boardId = "11111111-1111-4111-8111-111111111111"

afterEach(() => vi.unstubAllGlobals())

describe("fetchCurrentBackground", () => {
  it("Given current private image When loaded and reloaded Then typed blobs are returned", async () => {
    const request = vi.fn(async () => new Response(new Uint8Array([1, 2, 3]), {
      headers: { "Content-Type": "image/png" },
    }))
    vi.stubGlobal("fetch", request)

    const initial = await fetchCurrentBackground(boardId)
    const reloaded = await fetchCurrentBackground(boardId)

    expect(initial).toBeInstanceOf(Blob)
    expect(initial?.type).toBe("image/png")
    expect(reloaded).toBeInstanceOf(Blob)
    expect(request).toHaveBeenCalledTimes(2)
  })

  it("Given no current image When loaded Then null is returned", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 204 })))

    await expect(fetchCurrentBackground(boardId)).resolves.toBeNull()
  })

  it("Given a 404 response When loaded Then the read fails closed", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      code: "OBJECT_KEY_MISSING",
      objectKey: "private/owner/board.png",
    }), { headers: { "Content-Type": "application/json" }, status: 404 })))

    await expect(fetchCurrentBackground(boardId)).rejects.toMatchObject({ status: 404 })
  })
})
