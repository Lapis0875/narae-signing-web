import { z } from "zod";
import { apiRequest } from "../../../api/client.ts";
import {
  createPayload,
  type SignatureStroke,
} from "../pad/signaturePayload.ts";

const signatureSubmitResponseSchema = z.object({
  submitted: z.literal(true),
});

type SignatureSubmitResponse = z.infer<typeof signatureSubmitResponseSchema>;

type SignatureTransport = (path: string, init: RequestInit) => Promise<unknown>;

export async function submitSignature(
  strokes: readonly SignatureStroke[],
  transport: SignatureTransport = apiRequest,
): Promise<SignatureSubmitResponse> {
  const payload = createPayload(strokes);
  if (payload === null) {
    throw new SignaturePayloadError();
  }
  const response = await transport("/api/v1/public/signing-session/signature", {
    body: JSON.stringify(payload),
    headers: { "Content-Type": "application/json" },
    method: "POST",
  });
  const parsed = signatureSubmitResponseSchema.safeParse(response);
  if (!parsed.success) {
    throw new SignatureResponseError();
  }
  return parsed.data;
}

export class SignaturePayloadError extends Error {
  constructor() {
    super("Signature payload is invalid");
    this.name = "SignaturePayloadError";
  }
}

export class SignatureResponseError extends Error {
  constructor() {
    super("Signature response is invalid");
    this.name = "SignatureResponseError";
  }
}
