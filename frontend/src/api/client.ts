import type { z } from "zod";
import type { ApiError } from "./errors.ts";
import {
  apiErrorFromResponse,
  malformedResponseError,
  missingCsrfError,
  serviceUnavailableError,
} from "./errors.ts";

const safeMethods = new Set(["GET", "HEAD", "OPTIONS"]);
let csrfToken: string | null = null;

function csrfTokenFromCookie(): string | null {
  if (typeof document === "undefined") {
    return null;
  }
  const prefix = "XSRF-TOKEN=";
  return (
    document.cookie
      .split(";")
      .map((cookie) => cookie.trim())
      .find((cookie) => cookie.startsWith(prefix))
      ?.slice(prefix.length) || null
  );
}

export function setCsrfToken(token: string | null): void {
  csrfToken = token;
}

export async function apiRequest(
  path: string,
  init: RequestInit = {},
): Promise<unknown> {
  return (await apiResponse(path, init)).body;
}

export async function parsedApiRequest<T>(
  path: string,
  schema: z.ZodType<T>,
  init: RequestInit = {},
): Promise<T> {
  const response = await apiResponse(path, init);
  const parsed = schema.safeParse(response.body);
  if (parsed.success) {
    return parsed.data;
  }
  const error = malformedResponseError(response.requestId);
  reportFailure(path, response.method, error);
  throw error;
}

type ApiResponse = {
  readonly body: unknown;
  readonly method: string;
  readonly requestId: string | null;
};

async function apiResponse(path: string, init: RequestInit): Promise<ApiResponse> {
  const method = init.method?.toUpperCase() ?? "GET";
  const headers = new Headers(init.headers);

  if (!safeMethods.has(method)) {
    csrfToken = csrfTokenFromCookie() ?? csrfToken;
    if (csrfToken === null) {
      throw missingCsrfError();
    }
    headers.set("X-XSRF-TOKEN", csrfToken);
  }

  let response: Response;
  try {
    response = await fetch(path, { ...init, credentials: "same-origin", headers, method });
  } catch (error) {
    if (!(error instanceof TypeError)) throw error;
    const unavailable = serviceUnavailableError();
    reportFailure(path, method, unavailable);
    throw unavailable;
  }
  csrfToken = csrfTokenFromCookie();

  if (!response.ok) {
    const error = await apiErrorFromResponse(response);
    reportFailure(path, method, error);
    throw error;
  }

  if (response.status === 204) {
    return { body: null, method, requestId: requestIdFromResponse(response) };
  }

  try {
    return { body: await response.json(), method, requestId: requestIdFromResponse(response) };
  } catch (error) {
    if (!(error instanceof SyntaxError)) throw error;
    const malformed = malformedResponseError(requestIdFromResponse(response));
    reportFailure(path, method, malformed);
    throw malformed;
  }
}

function requestIdFromResponse(response: Response): string | null {
  const value = response.headers.get("X-Request-ID");
  return value !== null && /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/u.test(value) ? value : null;
}

function reportFailure(path: string, method: string, error: ApiError): void {
  if (!import.meta.env.DEV) return;
  const route = new URL(path, "http://local.invalid").pathname
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/giu, ":id")
    .replace(/(\/api\/v1\/public\/links\/)[^/]+/u, "$1:token");
  console.error("api_request_failed", {
    code: error.code,
    method,
    requestId: error.requestId,
    route,
    status: error.status,
  });
}
