import { apiRequest } from "../../../api/client.ts"
import type { SignaturePayload } from "../pad/signaturePayload.ts"

export async function updateSignatureDraft(payload: SignaturePayload): Promise<void> {
  await apiRequest("/api/v1/public/signing-session/draft", {
    body: JSON.stringify(payload),
    headers: { "Content-Type": "application/json" },
    method: "PUT",
  })
}

export async function clearSignatureDraft(): Promise<void> {
  await apiRequest("/api/v1/public/signing-session/draft/clear", { method: "POST" })
}

export async function cancelSignatureDraft(): Promise<void> {
  await apiRequest("/api/v1/public/signing-session/cancel", { method: "POST" })
}
