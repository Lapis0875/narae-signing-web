import { beforeEach, describe, expect, it, vi } from "vitest"
import { setCsrfToken } from "../../api/client.ts"
import { ApiError } from "../../api/errors.ts"
import { login } from "./authApi.ts"

describe("login error semantics", () => {
  beforeEach(() => {
    Reflect.set(document, "cookie", "XSRF-TOKEN=csrf-test; Path=/")
    setCsrfToken(null)
    vi.restoreAllMocks()
  })

  it("preserves rate-limit code, status, request ID, and retry metadata", async () => {
    // Given
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        code: "LOGIN_RATE_LIMITED",
        message: "private backend detail",
        requestId: "login-request-32",
      }), {
        headers: { "Content-Type": "application/json", "Retry-After": "23" },
        status: 429,
      }))

    // When
    const request = login("operator@example.com", "private-password")

    // Then
    await expect(request).rejects.toMatchObject({
      code: "LOGIN_RATE_LIMITED",
      message: "로그인에 실패했습니다. 입력 정보를 확인해 주세요.",
      requestId: "login-request-32",
      retryAfterSeconds: 23,
      status: 429,
    })
    await expect(request).rejects.toBeInstanceOf(ApiError)
    await expect(request).rejects.not.toMatchObject({ message: expect.stringContaining("private backend detail") })
  })
})
