import { z } from "zod"
import { apiRequest } from "../../../api/client.ts"

const titledLinkSchema = z.object({
  state: z.enum(["SETUP", "OPEN", "CLOSED"]),
  title: z.string().min(1).max(200),
})
const invalidLinkSchema = z.object({
  state: z.literal("INVALID"),
  title: z.null().optional(),
})
const publicLinkSchema = z.union([
  titledLinkSchema,
  invalidLinkSchema,
])

const identifyResponseSchema = z.object({ identified: z.literal(true) })
const signingSessionSchema = z.object({
  signatureAspectRatio: z.number().finite().positive().max(1000).optional(),
  state: z.enum(["READY", "SUBMITTED", "CLOSED", "BUSY", "STALE", "INVALID"]),
})

export type PublicLink = z.infer<typeof publicLinkSchema>
export type SigningSession = z.infer<typeof signingSessionSchema>

export type SignerIdentity = {
  readonly organization: string
  readonly job: string
  readonly name: string
}

export async function readPublicLink(shareToken: string): Promise<PublicLink> {
  return publicLinkSchema.parse(
    await apiRequest(`/api/v1/public/links/${encodeURIComponent(shareToken)}`),
  )
}

export async function identifySigner(
  shareToken: string,
  identity: SignerIdentity,
): Promise<void> {
  identifyResponseSchema.parse(
    await apiRequest(
      `/api/v1/public/links/${encodeURIComponent(shareToken)}/identify`,
      {
        body: JSON.stringify(identity),
        headers: { "Content-Type": "application/json" },
        method: "POST",
      },
    ),
  )
}

export async function readSigningSession(): Promise<SigningSession> {
  return signingSessionSchema.parse(
    await apiRequest("/api/v1/public/signing-session"),
  )
}
