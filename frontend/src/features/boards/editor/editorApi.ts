import { z } from "zod"
import { apiRequest, parsedApiRequest } from "../../../api/client.ts"
import { apiErrorFromResponse } from "../../../api/errors.ts"
import { boardSchema, type Board } from "../list/boardApi.ts"
import { normalizedBoundsSchema, type Bounds } from "./geometry.ts"

const slotBackgroundSchema = z.enum(["transparent", "white"])
const slotResponseSchema = z.strictObject({
  background: z.enum(["TRANSPARENT", "WHITE"]),
  boardId: z.uuid(),
  bounds: normalizedBoundsSchema,
  id: z.uuid(),
  revision: z.number().int().nonnegative(),
  rosterEntryId: z.uuid(),
  signaturePresent: z.boolean(),
  submitted: z.boolean(),
})
const shareSchema = z.strictObject({
  shareToken: z.string().min(1),
  version: z.number().int().positive(),
})
const backgroundSchema = z.strictObject({
  displayHeight: z.number().int().positive(),
  displayWidth: z.number().int().positive(),
  id: z.uuid(),
  mimeType: z.enum(["image/png", "image/jpeg"]),
})

export type SlotBackground = z.infer<typeof slotBackgroundSchema>
export type SlotResponse = z.infer<typeof slotResponseSchema>
export type Share = z.infer<typeof shareSchema>
export type Background = z.infer<typeof backgroundSchema>

export async function fetchBoardDetail(boardId: string): Promise<Board> {
  return parsedApiRequest(`/api/v1/admin/boards/${z.uuid().parse(boardId)}`, boardSchema)
}

export async function fetchCurrentBackground(boardId: string): Promise<Blob | null> {
  const response = await fetch(`/api/v1/admin/boards/${z.uuid().parse(boardId)}/background`, {
    credentials: "same-origin",
  })
  if (response.status === 204) return null
  if (!response.ok) throw await apiErrorFromResponse(response)
  const mimeType = z.enum(["image/png", "image/jpeg"])
    .parse(response.headers.get("Content-Type")?.split(";").at(0))
  return new Blob([await response.arrayBuffer()], { type: mimeType })
}

export async function renameBoard(boardId: string, title: string): Promise<Board> {
  return parsedApiRequest(`/api/v1/admin/boards/${z.uuid().parse(boardId)}`, boardSchema, {
    body: JSON.stringify(z.strictObject({ title: z.string().min(1) }).parse({ title })),
    headers: { "Content-Type": "application/json" },
    method: "PATCH",
  })
}

export async function saveSlot(
  boardId: string,
  slotId: string,
  bounds: Bounds,
  background: SlotBackground,
): Promise<SlotResponse> {
  const body = z.strictObject({
    background: slotBackgroundSchema,
    height: z.number(),
    width: z.number(),
    x: z.number(),
    y: z.number(),
  }).parse({ ...normalizedBoundsSchema.parse(bounds), background })
  return parsedApiRequest(
    `/api/v1/admin/boards/${z.uuid().parse(boardId)}/slots/${z.uuid().parse(slotId)}`,
    slotResponseSchema,
    { body: JSON.stringify(body), headers: { "Content-Type": "application/json" }, method: "PATCH" },
  )
}

export async function unplaceSlot(boardId: string, slotId: string): Promise<void> {
  await apiRequest(
    `/api/v1/admin/boards/${z.uuid().parse(boardId)}/slots/${z.uuid().parse(slotId)}`,
    { method: "DELETE" },
  )
}

export async function fetchShare(boardId: string): Promise<Share> {
  return parsedApiRequest(`/api/v1/admin/boards/${z.uuid().parse(boardId)}/share`, shareSchema)
}

export async function reissueShare(boardId: string): Promise<Share> {
  return parsedApiRequest(
    `/api/v1/admin/boards/${z.uuid().parse(boardId)}/share/reissue`,
    shareSchema,
    { method: "POST" },
  )
}

export async function uploadBackground(
  boardId: string,
  file: File,
  adoptSourceRatio: boolean,
  confirmed: boolean,
): Promise<Background> {
  const body = new FormData()
  body.append("file", file)
  body.append("adoptSourceRatio", String(adoptSourceRatio))
  body.append("confirmed", String(confirmed))
  return parsedApiRequest(
    `/api/v1/admin/boards/${z.uuid().parse(boardId)}/background`,
    backgroundSchema,
    { body, method: "POST" },
  )
}

export async function transitionBoard(
  boardId: string,
  action: "open" | "close" | "reopen",
): Promise<Board> {
  return parsedApiRequest(
    `/api/v1/admin/boards/${z.uuid().parse(boardId)}/${action}`,
    boardSchema,
    { method: "POST" },
  )
}
