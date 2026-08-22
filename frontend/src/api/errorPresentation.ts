import { z } from "zod"
import { ApiError } from "./errors.ts"

const guidanceCodes = [
  "AUTHENTICATION_FAILED", "BACKGROUND_UNAVAILABLE", "BLANK_NAME", "BOARD_CLOSED",
  "CONFLICT", "CSRF_INVALID", "CSRF_MISSING", "DUPLICATE_IDENTITY", "FIELD_TOO_LONG",
  "FILE_TOO_LARGE", "FORBIDDEN", "INVALID_CLIENT_IP", "INVALID_ENCODING", "INVALID_FIELDS",
  "INVALID_HEADER", "INVALID_JSON", "INVALID_MULTIPART", "INVALID_SHEET_COUNT", "INVALID_XLSX",
  "LINK_INVALID", "LOGIN_RATE_LIMITED", "MALFORMED_RESPONSE", "NOT_FOUND", "RATE_LIMITED",
  "ROSTER_INVALID", "ROSTER_UNAVAILABLE", "ROW_LIMIT", "SERVICE_UNAVAILABLE", "SIGNER_STALE",
  "UNAUTHORIZED", "UNKNOWN", "UNSUPPORTED_FILE_TYPE",
] as const
const guidanceCodeSchema = z.enum(guidanceCodes)
type GuidanceCode = z.infer<typeof guidanceCodeSchema>

const guidance = {
  AUTHENTICATION_FAILED: "로그인에 실패했습니다. 이메일과 비밀번호를 확인해 주세요.",
  BACKGROUND_UNAVAILABLE: "배경을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.",
  BLANK_NAME: "이름을 입력해 주세요.",
  BOARD_CLOSED: "현재 서명을 진행할 수 없습니다.",
  CONFLICT: "현재 상태가 변경되었습니다. 화면을 확인한 뒤 다시 시도해 주세요.",
  CSRF_INVALID: "페이지를 새로고침한 뒤 다시 시도해 주세요.",
  CSRF_MISSING: "페이지를 새로고침한 뒤 다시 시도해 주세요.",
  DUPLICATE_IDENTITY: "같은 명단이 중복되었습니다.\n소속, 직책, 이름을 확인해 주세요.",
  FIELD_TOO_LONG: "입력값을 줄인 뒤 다시 시도해 주세요.",
  FILE_TOO_LARGE: "파일 크기를 확인한 뒤 다시 시도해 주세요.",
  FORBIDDEN: "접근 권한이 없습니다.",
  INVALID_CLIENT_IP: "로컬 접속 환경의 프록시 설정을 확인해 주세요.",
  INVALID_ENCODING: "UTF-8 CSV 또는 한 개 시트의 XLSX 파일을 선택해 주세요.",
  INVALID_FIELDS: "소속, 직책, 이름 세 필드를 확인해 주세요.",
  INVALID_HEADER: "CSV 머리글을 확인해 주세요.",
  INVALID_JSON: "붙여넣은 명단 형식을 확인해 주세요.",
  INVALID_MULTIPART: "파일을 다시 선택해 주세요.",
  INVALID_SHEET_COUNT: "시트가 한 개인 XLSX 파일을 선택해 주세요.",
  INVALID_XLSX: "XLSX 파일을 다시 확인해 주세요.",
  LINK_INVALID: "이 링크에서는 서명을 진행할 수 없습니다.",
  LOGIN_RATE_LIMITED: "로그인에 실패했습니다. 이메일과 비밀번호를 확인해 주세요.",
  MALFORMED_RESPONSE: "서버 응답 확인에 실패했습니다. 다시 시도해 주세요.",
  NOT_FOUND: "요청한 정보를 찾을 수 없습니다.",
  RATE_LIMITED: "요청이 많습니다. 잠시 후 다시 시도해 주세요.",
  ROSTER_INVALID: "명단 입력을 확인한 뒤 다시 시도해 주세요.",
  ROSTER_UNAVAILABLE: "현재 명단을 변경할 수 없습니다. 보드 상태를 확인해 주세요.",
  ROW_LIMIT: "명단은 50명까지 등록할 수 있습니다.",
  SERVICE_UNAVAILABLE: "서비스에 연결할 수 없습니다. 연결 상태를 확인하고 다시 시도해 주세요.",
  SIGNER_STALE: "서명 정보를 다시 입력해 주세요.",
  UNAUTHORIZED: "세션이 만료되었습니다. 다시 로그인해 주세요.",
  UNKNOWN: "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
  UNSUPPORTED_FILE_TYPE: "CSV 또는 XLSX 파일을 선택해 주세요.",
} as const satisfies Record<GuidanceCode, string>

type ErrorContext = "default" | "login"
export type ErrorPresentation = {
  readonly code: GuidanceCode
  readonly message: string
  readonly requestId: string | null
  readonly status: number
}

function guidanceCode(value: string): GuidanceCode | null {
  const parsed = guidanceCodeSchema.safeParse(value)
  return parsed.success ? parsed.data : null
}

export function presentError(error: unknown, context: ErrorContext = "default"): ErrorPresentation {
  if (context === "login" && error instanceof ApiError) {
    return { code: "AUTHENTICATION_FAILED", message: guidance.AUTHENTICATION_FAILED, requestId: error.requestId, status: error.status }
  }
  if (error instanceof TypeError) {
    return { code: "SERVICE_UNAVAILABLE", message: guidance.SERVICE_UNAVAILABLE, requestId: null, status: 0 }
  }
  if (!(error instanceof ApiError)) {
    return { code: "MALFORMED_RESPONSE", message: guidance.MALFORMED_RESPONSE, requestId: null, status: 0 }
  }
  const detailCode = guidanceCode(error.details.at(0)?.code ?? "")
  const code = detailCode ?? guidanceCode(error.code) ?? "UNKNOWN"
  const requestReference = error.requestId === null ? "" : `\n요청 ID: ${error.requestId}`
  return {
    code,
    message: `${guidance[code]}${requestReference}`,
    requestId: error.requestId,
    status: error.status,
  }
}
