import { z } from "zod"
import { apiRequest, parsedApiRequest } from "../../../api/client.ts"
import type { SignaturePayload } from "../pad/signaturePayload.ts"

const signatureDraftVersionSchema = z.object({
  draftEpoch: z.number().int().nonnegative(),
  revision: z.number().int().nonnegative(),
})

export type SignatureDraftVersion = Readonly<z.infer<typeof signatureDraftVersionSchema>>

export type SignatureDraftDelta = {
  readonly clientSequence: number
  readonly draftEpoch: number
  readonly operation: "append" | "begin" | "end"
  readonly points: readonly { readonly x: number; readonly y: number }[]
  readonly revision: number
  readonly strokeIndex: number
}

export async function updateSignatureDraft(payload: SignaturePayload): Promise<SignatureDraftVersion> {
  return parsedApiRequest("/api/v1/public/signing-session/draft", signatureDraftVersionSchema, {
    body: JSON.stringify(payload),
    headers: { "Content-Type": "application/json" },
    method: "PUT",
  })
}

export async function appendSignatureDraft(delta: SignatureDraftDelta): Promise<SignatureDraftVersion> {
  return parsedApiRequest("/api/v1/public/signing-session/draft/delta", signatureDraftVersionSchema, {
    body: JSON.stringify({
      operation: delta.operation,
      clientSequence: delta.clientSequence,
      draftEpoch: delta.draftEpoch,
      revision: delta.revision,
      strokeIndex: delta.strokeIndex,
      points: delta.points,
    }),
    headers: { "Content-Type": "application/json" },
    method: "POST",
  })
}

export async function clearSignatureDraft(): Promise<void> {
  await apiRequest("/api/v1/public/signing-session/draft/clear", { method: "POST" })
}

export async function cancelSignatureDraft(): Promise<void> {
  await apiRequest("/api/v1/public/signing-session/cancel", { method: "POST" })
}
