import { z } from "zod"
import { apiRequest, parsedApiRequest, setCsrfToken } from "../../api/client.ts"

const authenticatedSessionSchema = z.object({
  authenticated: z.literal(true),
  expiresAt: z.iso.datetime(),
})

const sessionSchema = z.discriminatedUnion("authenticated", [
  authenticatedSessionSchema,
  z.object({ authenticated: z.literal(false), expiresAt: z.null() }),
])

export const authSessionQueryKey = ["auth", "session"] as const

export type AuthSession = z.infer<typeof sessionSchema>
export type AuthenticatedSession = z.infer<typeof authenticatedSessionSchema>

class CsrfTokenUnavailableError extends Error {
  constructor() {
    super("CSRF token unavailable")
    this.name = "CsrfTokenUnavailableError"
  }
}

function csrfTokenFromCookie(): string {
  const prefix = "XSRF-TOKEN="
  const csrfCookieValue = document.cookie
    .split("; ")
    .find((cookie) => cookie.startsWith(prefix))
    ?.slice(prefix.length)

  if (csrfCookieValue === undefined || csrfCookieValue.length === 0) {
    throw new CsrfTokenUnavailableError()
  }
  return csrfCookieValue
}

async function csrfToken(): Promise<string> {
  await apiRequest("/api/v1/auth/csrf")
  const csrfCookieValue = csrfTokenFromCookie()
  setCsrfToken(csrfCookieValue)
  return csrfCookieValue
}

export async function fetchAuthSession(): Promise<AuthSession> {
  return parsedApiRequest("/api/v1/auth/session", sessionSchema)
}

export async function login(email: string, password: string): Promise<AuthenticatedSession> {
  const csrfCookieValue = await csrfToken()
  const session = await parsedApiRequest("/api/v1/auth/login", authenticatedSessionSchema, {
    body: JSON.stringify({ email, password }),
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrfCookieValue },
    method: "POST",
  })
  setCsrfToken(csrfTokenFromCookie())
  return session
}

export async function logout(): Promise<void> {
  const csrfCookieValue = await csrfToken()
  await apiRequest("/api/v1/auth/logout", {
    headers: { "X-XSRF-TOKEN": csrfCookieValue },
    method: "POST",
  })
  setCsrfToken(null)
}
