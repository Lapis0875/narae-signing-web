import { z } from "zod"

const errorMessages = {
  CSRF_MISSING: "보안 토큰이 없습니다. 페이지를 새로고침해 주세요.",
  UNAUTHORIZED: "로그인이 필요합니다.",
  FORBIDDEN: "접근 권한이 없습니다.",
  NOT_FOUND: "요청한 정보를 찾을 수 없습니다.",
  CONFLICT: "현재 상태가 변경되었습니다. 다시 시도해 주세요.",
  UNKNOWN: "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
} as const

const apiErrorSchema = z.object({
  code: z.enum(["CSRF_MISSING", "UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND", "CONFLICT", "UNKNOWN"]),
})

export type ApiErrorCode = keyof typeof errorMessages

export class ApiError extends Error {
  readonly code: ApiErrorCode
  readonly status: number

  constructor(code: ApiErrorCode, message: string, status: number) {
    super(message)
    this.name = "ApiError"
    this.code = code
    this.status = status
  }
}

function codeFromStatus(status: number): ApiErrorCode {
  switch (status) {
    case 401:
      return "UNAUTHORIZED"
    case 403:
      return "FORBIDDEN"
    case 404:
      return "NOT_FOUND"
    case 409:
      return "CONFLICT"
    default:
      return "UNKNOWN"
  }
}

export async function apiErrorFromResponse(response: Response): Promise<ApiError> {
  const isJson = response.headers.get("content-type")?.includes("application/json") ?? false
  const body: unknown = isJson ? await response.json() : null
  const parsed = apiErrorSchema.safeParse(body)
  const code = parsed.success ? parsed.data.code : codeFromStatus(response.status)

  return new ApiError(code, errorMessages[code], response.status)
}

export function missingCsrfError(): ApiError {
  return new ApiError("CSRF_MISSING", errorMessages.CSRF_MISSING, 0)
}
