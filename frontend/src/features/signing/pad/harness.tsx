import { StrictMode, useState } from "react";
import { createRoot } from "react-dom/client";
import { ApiError } from "../../../api/errors.ts";
import "../../../styles.css";
import {
  appendSignatureDraft,
  type SignatureDraftDelta,
  type SignatureDraftVersion,
  updateSignatureDraft,
} from "../api/signatureDraftApi.ts";
import { SignaturePad } from "./index.ts";
import type { SignaturePayload } from "./signaturePayload.ts";

async function syncFull(
  payload: SignaturePayload,
): Promise<SignatureDraftVersion | null> {
  try {
    return await updateSignatureDraft(payload);
  } catch (error) {
    if (error instanceof ApiError) return null;
    throw error;
  }
}

async function syncDelta(
  delta: SignatureDraftDelta,
): Promise<SignatureDraftVersion | null> {
  try {
    return await appendSignatureDraft(delta);
  } catch (error) {
    if (error instanceof ApiError) return null;
    throw error;
  }
}

function summarize(payload: SignaturePayload): string {
  let pointCount = 0;
  let checksum = 0;
  let matchingAdjacentStrokes = 0;
  for (const [strokeIndex, stroke] of payload.strokes.entries()) {
    pointCount += stroke.points.length;
    for (const point of stroke.points) {
      checksum = (checksum + point.x * 31 + point.y * 17) % 1_000_000_007;
    }
    const previous = payload.strokes[strokeIndex - 1];
    if (
      previous !== undefined &&
      JSON.stringify(previous) === JSON.stringify(stroke)
    ) {
      matchingAdjacentStrokes += 1;
    }
  }
  return `v=${payload.version};strokes=${payload.strokes.length};points=${pointCount};checksum=${checksum};matching=${matchingAdjacentStrokes}`;
}

function Harness() {
  const [rejectSubmission, setRejectSubmission] = useState(false);
  const [summary, setSummary] = useState("No submission");

  const submit = async (nextPayload: SignaturePayload) => {
    setSummary(summarize(nextPayload));
    if (rejectSubmission) {
      throw new Error("Harness rejected signature submission");
    }
    return true;
  };
  const draftSync = new URLSearchParams(location.search).has("draft-sync");

  return (
    <main className="app-shell">
      <section className="app-main">
        <button
          aria-pressed={rejectSubmission}
          className="app-primary-button signature-pad-harness__toggle"
          onClick={() => setRejectSubmission((value) => !value)}
          type="button"
        >
          제출 거부 {rejectSubmission ? "켜짐" : "꺼짐"}
        </button>
        <SignaturePad
          {...(draftSync ? { onDraft: syncFull, onDraftDelta: syncDelta } : {})}
          onSubmit={submit}
        />
        <output data-testid="payload-summary" hidden>
          {summary}
        </output>
      </section>
    </main>
  );
}

const rootElement = document.getElementById("root");
if (rootElement === null) {
  throw new Error("Signature pad harness root is missing");
}
createRoot(rootElement).render(
  <StrictMode>
    <Harness />
  </StrictMode>,
);
