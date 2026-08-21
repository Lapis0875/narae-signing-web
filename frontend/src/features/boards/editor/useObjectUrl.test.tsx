import { renderHook } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"
import { useObjectUrl } from "./useObjectUrl.ts"

afterEach(() => vi.unstubAllGlobals())

describe("useObjectUrl", () => {
  it("Given reloaded and replaced blobs When changed or unmounted Then every URL is revoked", () => {
    const createObjectURL = vi.fn().mockReturnValueOnce("blob:first").mockReturnValueOnce("blob:second")
    const revokeObjectURL = vi.fn()
    vi.stubGlobal("URL", { createObjectURL, revokeObjectURL })
    const first = new Blob(["first"], { type: "image/png" })
    const second = new Blob(["second"], { type: "image/png" })

    const hook = renderHook(({ blob }) => useObjectUrl(blob), { initialProps: { blob: first } })
    hook.rerender({ blob: second })
    hook.unmount()

    expect(createObjectURL).toHaveBeenCalledTimes(2)
    expect(revokeObjectURL).toHaveBeenNthCalledWith(1, "blob:first")
    expect(revokeObjectURL).toHaveBeenNthCalledWith(2, "blob:second")
  })
})
