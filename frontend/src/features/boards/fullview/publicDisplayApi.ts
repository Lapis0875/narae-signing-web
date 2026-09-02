import { z } from "zod"
import { apiRequest } from "../../../api/client.ts"
import { apiErrorFromResponse } from "../../../api/errors.ts"
import { fullViewSnapshotSchema, type FullViewSnapshot } from "./fullViewApi.ts"

function displayPath(shareToken: string, suffix: string): string {
  return `/api/v1/public/links/${encodeURIComponent(shareToken)}/display/${suffix}`
}

export async function fetchPublicDisplaySnapshot(shareToken: string): Promise<FullViewSnapshot> {
  return fullViewSnapshotSchema.parse(await apiRequest(displayPath(shareToken, "snapshot")))
}

export async function fetchPublicDisplayBackground(shareToken: string): Promise<Blob | null> {
  const response = await fetch(displayPath(z.string().min(1).parse(shareToken), "background"), {
    credentials: "same-origin",
    referrerPolicy: "no-referrer",
  })
  if (response.status === 204) return null
  if (!response.ok) throw await apiErrorFromResponse(response)
  return response.blob()
}
