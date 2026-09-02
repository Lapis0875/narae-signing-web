import { z } from "zod"
import { apiRequest } from "../../../api/client.ts"
import { apiErrorFromResponse } from "../../../api/errors.ts"

const pointSchema = z.strictObject({ x: z.number().int().min(0).max(1_000_000), y: z.number().int().min(0).max(1_000_000) })
const signatureSchema = z.strictObject({
  strokes: z.array(z.strictObject({ points: z.array(pointSchema).min(1) })).max(128),
  version: z.literal(1),
})
const slotSchema = z.strictObject({
  background: z.enum(["transparent", "white"]),
  draftSignature: signatureSchema.nullable(),
  height: z.number().positive(),
  id: z.uuid(),
  signature: signatureSchema.nullable(),
  width: z.number().positive(),
  x: z.number().min(0),
  y: z.number().min(0),
})
export const fullViewSnapshotSchema = z.strictObject({
  backgroundPresent: z.boolean(),
  boardId: z.uuid(),
  canvasHeight: z.number().int().positive(),
  canvasWidth: z.number().int().positive(),
  slots: z.array(slotSchema),
})

export type FullViewSnapshot = z.infer<typeof fullViewSnapshotSchema>
export type FullViewSlot = z.infer<typeof slotSchema>

export async function fetchFullViewSnapshot(boardId: string): Promise<FullViewSnapshot> {
  return fullViewSnapshotSchema.parse(await apiRequest(`/api/v1/admin/boards/${z.uuid().parse(boardId)}/snapshot`))
}

export async function fetchFullViewBackground(boardId: string): Promise<Blob | null> {
  const response = await fetch(`/api/v1/admin/boards/${z.uuid().parse(boardId)}/background`, {
    credentials: "same-origin",
    referrerPolicy: "no-referrer",
  })
  if (response.status === 204) return null
  if (!response.ok) throw await apiErrorFromResponse(response)
  return response.blob()
}
