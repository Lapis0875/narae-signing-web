import { z } from "zod";

const errorMessages = {
  AUTHENTICATION_FAILED: "로그인에 실패했습니다. 입력 정보를 확인해 주세요.",
  BOARD_CLOSED: "현재 서명을 진행할 수 없습니다.",
  CSRF_INVALID: "요청을 확인할 수 없습니다. 페이지를 새로고침해 주세요.",
  CSRF_MISSING: "보안 토큰이 없습니다. 페이지를 새로고침해 주세요.",
  SIGNER_STALE: "서명 정보를 다시 입력해 주세요.",
  FORBIDDEN: "접근 권한이 없습니다.",
  LINK_INVALID: "이 링크에서는 서명을 진행할 수 없습니다.",
  NOT_FOUND: "요청한 정보를 찾을 수 없습니다.",
  CONFLICT: "현재 상태가 변경되었습니다. 다시 시도해 주세요.",
  RATE_LIMITED: "요청이 많습니다. 잠시 후 다시 시도해 주세요.",
  UNAUTHORIZED: "로그인이 필요합니다.",
  UNKNOWN: "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
} as const;

const apiErrorSchema = z.object({
  code: z.string(),
});
const apiErrorDetailsSchema = z.object({
  errors: z.array(z.strictObject({
    code: z.string().min(1).max(64).regex(/^[A-Z][A-Z0-9_]*$/u),
    row: z.number().int().nonnegative().max(50),
  })).max(50),
});

export type ApiErrorCode = keyof typeof errorMessages;
export type ApiErrorDetail = {
  readonly code: string;
  readonly row: number;
};

export class ApiError extends Error {
  readonly code: ApiErrorCode;
  readonly details: readonly ApiErrorDetail[];
  readonly retryAfterSeconds: number | null;
  readonly status: number;

  constructor(
    code: ApiErrorCode,
    message: string,
    status: number,
    retryAfterSeconds: number | null = null,
    details: readonly ApiErrorDetail[] = [],
  ) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.details = details;
    this.retryAfterSeconds = retryAfterSeconds;
    this.status = status;
  }
}

function codeFromBackend(code: string, status: number): ApiErrorCode {
  switch (code) {
    case "AUTHENTICATION_FAILED":
      return "AUTHENTICATION_FAILED";
    case "board_closed":
      return "BOARD_CLOSED";
    case "csrf_invalid":
      return "CSRF_INVALID";
    case "link_invalid":
      return "LINK_INVALID";
    case "LOGIN_RATE_LIMITED":
    case "rate_limited":
      return "RATE_LIMITED";
    case "signer_stale":
      return "SIGNER_STALE";
    default:
      return codeFromStatus(status);
  }
}

function codeFromStatus(status: number): ApiErrorCode {
  switch (status) {
    case 401:
      return "UNAUTHORIZED";
    case 403:
      return "FORBIDDEN";
    case 404:
      return "NOT_FOUND";
    case 409:
      return "CONFLICT";
    case 429:
      return "RATE_LIMITED";
    default:
      return "UNKNOWN";
  }
}

function retryAfterSeconds(response: Response): number | null {
  const value = response.headers.get("Retry-After");
  if (value === null) {
    return null;
  }
  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0) {
    return Math.ceil(seconds);
  }
  const timestamp = Date.parse(value);
  return Number.isNaN(timestamp)
    ? null
    : Math.max(0, Math.ceil((timestamp - Date.now()) / 1_000));
}

export async function apiErrorFromResponse(
  response: Response,
): Promise<ApiError> {
  const isJson =
    response.headers.get("content-type")?.includes("application/json") ?? false;
  let body: unknown = null;
  if (isJson) {
    try {
      body = await response.json();
    } catch (error) {
      if (!(error instanceof SyntaxError)) {
        throw error;
      }
    }
  }
  const parsed = apiErrorSchema.safeParse(body);
  const parsedDetails = apiErrorDetailsSchema.safeParse(body);
  const code = parsed.success
    ? codeFromBackend(parsed.data.code, response.status)
    : codeFromStatus(response.status);

  return new ApiError(
    code,
    errorMessages[code],
    response.status,
    retryAfterSeconds(response),
    parsedDetails.success ? parsedDetails.data.errors : [],
  );
}

export function missingCsrfError(): ApiError {
  return new ApiError("CSRF_MISSING", errorMessages.CSRF_MISSING, 0);
}
