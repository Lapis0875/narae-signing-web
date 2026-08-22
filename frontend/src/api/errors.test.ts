import { describe, expect, it } from "vitest"
import { presentError } from "./errorPresentation.ts"
import { ApiError, apiErrorFromResponse } from "./errors.ts"

describe("apiErrorFromResponse baseline", () => {
  it("Given backend prose When an API failure is parsed Then the prose is not exposed", async () => {
    // Given
    const response = new Response(JSON.stringify({
      code: "AUTHENTICATION_FAILED",
      message: "unknown account operator@example.com",
    }), {
      headers: { "Content-Type": "application/json" },
      status: 401,
    })

    // When
    const error = await apiErrorFromResponse(response)

    // Then
    expect(error.message).toBe("로그인에 실패했습니다. 입력 정보를 확인해 주세요.")
    expect(error.message).not.toContain("operator@example.com")
  })

  it("Given an unsafe request ID When a failure is parsed Then the correlation value is omitted", async () => {
    // Given
    const response = new Response(JSON.stringify({ code: "INTERNAL_ERROR", requestId: "unsafe request id" }), {
      headers: { "Content-Type": "application/json", "X-Request-ID": "also unsafe" },
      status: 503,
    })

    // When
    const error = await apiErrorFromResponse(response)

    // Then
    expect(error).toMatchObject({ code: "SERVICE_UNAVAILABLE", requestId: null, status: 503 })
  })
})

describe("presentError", () => {
  it.each([
    new ApiError("AUTHENTICATION_FAILED", "ignored", 401),
    new ApiError("LOGIN_RATE_LIMITED", "ignored", 429),
  ])("Given an unsafe login distinction When it is presented Then the message stays indistinguishable", (error) => {
    // Given / When
    const presentation = presentError(error, "login")

    // Then
    expect(presentation.message).toBe("로그인에 실패했습니다. 이메일과 비밀번호를 확인해 주세요.")
  })

  it.each([
    [new ApiError("UNAUTHORIZED", "ignored", 401), "세션이 만료되었습니다. 다시 로그인해 주세요."],
    [new ApiError("CSRF_INVALID", "ignored", 403), "페이지를 새로고침한 뒤 다시 시도해 주세요."],
    [new ApiError("INVALID_CLIENT_IP", "ignored", 400), "로컬 접속 환경의 프록시 설정을 확인해 주세요."],
    [new ApiError("ROSTER_INVALID", "ignored", 400, null, [{ code: "BLANK_NAME", row: 1 }]), "이름을 입력해 주세요."],
    [new ApiError("ROSTER_INVALID", "ignored", 400, null, [{ code: "DUPLICATE_IDENTITY", row: 1 }]), "같은 명단이 중복되었습니다.\n소속, 직책, 이름을 확인해 주세요."],
    [new ApiError("ROSTER_INVALID", "ignored", 400, null, [{ code: "ROW_LIMIT", row: 51 }]), "명단은 50명까지 등록할 수 있습니다."],
    [new TypeError("private network endpoint"), "서비스에 연결할 수 없습니다. 연결 상태를 확인하고 다시 시도해 주세요."],
  ])("Given a safe error class When it is presented Then Korean recovery guidance is returned", (error, message) => {
    // Given / When
    const presentation = presentError(error)

    // Then
    expect(presentation.message).toBe(message)
    expect(presentation.message).not.toContain("private")
  })

  it("Given a malformed response with a safe request ID When it is presented Then raw parser text stays hidden", () => {
    // Given
    const error = new ApiError("MALFORMED_RESPONSE", "status: Invalid enum value", 200, null, [], "qa-request-32")

    // When
    const presentation = presentError(error)

    // Then
    expect(presentation.message).toBe("서버 응답 확인에 실패했습니다. 다시 시도해 주세요.\n요청 ID: qa-request-32")
    expect(presentation.message).not.toContain("Invalid enum")
  })
})
