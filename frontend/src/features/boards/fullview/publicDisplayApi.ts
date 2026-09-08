import { z } from "zod"
import { apiRequest } from "../../../api/client.ts"
import { ApiError, apiErrorFromResponse } from "../../../api/errors.ts"
import { fullViewSnapshotSchema, type FullViewSnapshot } from "./fullViewApi.ts"

export const DISPLAY_ALREADY_CONNECTED_MESSAGE = "다른 화면에서 이미 보드를 표시하고 있습니다."

const displayDeniedSchema = z.strictObject({
  code: z.literal("DISPLAY_ALREADY_CONNECTED"),
  message: z.literal(DISPLAY_ALREADY_CONNECTED_MESSAGE),
})
const displayTitleSchema = z.strictObject({ title: z.string().min(1).max(200) })

export class PublicDisplayDeniedError extends Error {
  constructor() {
    super(DISPLAY_ALREADY_CONNECTED_MESSAGE)
    this.name = "PublicDisplayDeniedError"
  }
}

export class PublicDisplayUnavailableError extends Error {
  constructor() {
    super("Public display link is unavailable")
    this.name = "PublicDisplayUnavailableError"
  }
}

function displayPath(shareToken: string, suffix: string): string {
  return `/api/v1/public/links/${encodeURIComponent(z.string().min(1).parse(shareToken))}/display/${suffix}`
}

function publicLinkPath(shareToken: string): string {
  return `/api/v1/public/links/${encodeURIComponent(z.string().min(1).parse(shareToken))}`
}

function csrfHeader(): HeadersInit {
  const token = document.cookie.split(";")
    .map((cookie) => cookie.trim())
    .find((cookie) => cookie.startsWith("XSRF-TOKEN="))
    ?.slice("XSRF-TOKEN=".length)
  return token === undefined || token.length === 0 ? {} : { "X-XSRF-TOKEN": token }
}

async function requestLease(shareToken: string, suffix: "claim" | "heartbeat"): Promise<void> {
  await apiRequest(publicLinkPath(shareToken))
  const response = await fetch(displayPath(shareToken, suffix), {
    credentials: "same-origin",
    headers: csrfHeader(),
    method: "POST",
    referrerPolicy: "no-referrer",
  })
  if (response.ok) return
  if (response.status === 409) {
    try {
      if (displayDeniedSchema.safeParse(await response.clone().json()).success) {
        throw new PublicDisplayDeniedError()
      }
    } catch (error) {
      if (error instanceof PublicDisplayDeniedError) throw error
      if (!(error instanceof SyntaxError)) throw error
    }
  }
  throw await apiErrorFromResponse(response)
}

export async function claimPublicDisplay(shareToken: string): Promise<null> {
  await requestLease(shareToken, "claim")
  return null
}

export async function heartbeatPublicDisplay(shareToken: string): Promise<"active" | "denied" | "revoked" | "unavailable"> {
  try {
    await requestLease(shareToken, "heartbeat")
    return "active"
  } catch (error) {
    if (error instanceof PublicDisplayDeniedError) return "denied"
    if (error instanceof ApiError && error.status === 404) return "revoked"
    if (error instanceof TypeError || error instanceof z.ZodError) return "unavailable"
    if (error instanceof ApiError) return "unavailable"
    throw error
  }
}

export async function releasePublicDisplay(shareToken: string): Promise<void> {
  try {
    await apiRequest(displayPath(shareToken, "release"), {
      keepalive: true,
      method: "POST",
      referrerPolicy: "no-referrer",
    })
  } catch (error) {
    if (error instanceof ApiError) return
    throw error
  }
}

export async function fetchPublicDisplayTitle(shareToken: string): Promise<string> {
  try {
    const body = await apiRequest(displayPath(shareToken, "title"))
    return displayTitleSchema.parse(body).title
  } catch (error) {
    terminalDisplayError(error)
  }
}

export async function fetchPublicDisplaySnapshot(shareToken: string): Promise<FullViewSnapshot> {
  try {
    return fullViewSnapshotSchema.parse(await apiRequest(displayPath(shareToken, "snapshot")))
  } catch (error) {
    terminalDisplayError(error)
  }
}

export async function fetchPublicDisplayBackground(shareToken: string): Promise<Blob | null> {
  try {
    const response = await fetch(displayPath(shareToken, "background"), {
      credentials: "same-origin",
      referrerPolicy: "no-referrer",
    })
    if (response.status === 204) return null
    if (!response.ok) throw await apiErrorFromResponse(response)
    return response.blob()
  } catch (error) {
    terminalDisplayError(error)
  }
}

function terminalDisplayError(error: unknown): never {
  if (error instanceof ApiError && error.status === 404) throw new PublicDisplayUnavailableError()
  throw error
}
