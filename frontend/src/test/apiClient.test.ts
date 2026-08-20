import { beforeEach, describe, expect, it, vi } from "vitest"
import { apiRequest, setCsrfToken } from "../api/client.ts"
import { ApiError } from "../api/errors.ts"

describe("apiRequest", () => {
  beforeEach(() => {
    setCsrfToken(null)
    vi.restoreAllMocks()
  })

  it("returns a stable Korean error without sending a state-changing request when CSRF is missing", async () => {
    // Given
    const fetchSpy = vi.spyOn(globalThis, "fetch")

    // When
    const request = apiRequest("/api/v1/admin/boards", { method: "POST" })

    // Then
    await expect(request).rejects.toEqual(
      new ApiError("CSRF_MISSING", "보안 토큰이 없습니다. 페이지를 새로고침해 주세요.", 0),
    )
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it("sends same-origin credentials and the CSRF header for a state-changing request", async () => {
    // Given
    setCsrfToken("test-csrf")
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ status: "ready" }), {
        headers: { "Content-Type": "application/json" },
      }),
    )

    // When
    await apiRequest("/api/v1/admin/boards", { method: "POST" })

    // Then
    expect(fetchSpy).toHaveBeenCalledOnce()
    expect(fetchSpy).toHaveBeenCalledWith(
      "/api/v1/admin/boards",
      expect.objectContaining({
        credentials: "same-origin",
        headers: expect.any(Headers),
        method: "POST",
      }),
    )
    const requestInit = fetchSpy.mock.lastCall?.[1]
    expect(new Headers(requestInit?.headers).get("X-CSRF-TOKEN")).toBe("test-csrf")
  })
})
