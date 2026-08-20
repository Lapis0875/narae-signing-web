import { apiErrorFromResponse, missingCsrfError } from "./errors.ts"

const safeMethods = new Set(["GET", "HEAD", "OPTIONS"])
let csrfToken: string | null = null

export function setCsrfToken(token: string | null): void {
  csrfToken = token
}

export async function apiRequest(path: string, init: RequestInit = {}): Promise<unknown> {
  const method = init.method?.toUpperCase() ?? "GET"
  const headers = new Headers(init.headers)

  if (!safeMethods.has(method)) {
    if (csrfToken === null) {
      throw missingCsrfError()
    }
    headers.set("X-CSRF-TOKEN", csrfToken)
  }

  const response = await fetch(path, {
    ...init,
    credentials: "same-origin",
    headers,
    method,
  })

  if (!response.ok) {
    throw await apiErrorFromResponse(response)
  }

  if (response.status === 204) {
    return null
  }

  return response.json()
}
