import { apiErrorFromResponse, missingCsrfError } from "./errors.ts";

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
  const method = init.method?.toUpperCase() ?? "GET";
  const headers = new Headers(init.headers);

  if (!safeMethods.has(method)) {
    csrfToken = csrfTokenFromCookie() ?? csrfToken;
    if (csrfToken === null) {
      throw missingCsrfError();
    }
    headers.set("X-XSRF-TOKEN", csrfToken);
  }

  const response = await fetch(path, {
    ...init,
    credentials: "same-origin",
    headers,
    method,
  });
  csrfToken = csrfTokenFromCookie();

  if (!response.ok) {
    throw await apiErrorFromResponse(response);
  }

  if (response.status === 204) {
    return null;
  }

  return response.json();
}
