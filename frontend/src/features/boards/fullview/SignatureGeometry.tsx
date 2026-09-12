import { memo, useCallback, useEffect, useRef } from "react";
import {
  calculatePreviewLineWidth,
  drawStroke,
} from "../../signing/pad/canvasRenderer.ts";
import type { SignaturePayload } from "../../signing/pad/signaturePayload.ts";
import type { FullViewSnapshot } from "./fullViewApi.ts";

type SignatureInkColor = FullViewSnapshot["signatureInkColor"];
type SignatureGeometryProps = {
  readonly inkColor: SignatureInkColor;
  readonly signature: SignaturePayload;
};

const SIGNATURE_INK_CSS = {
  black: "#000000",
  white: "#FFFFFF",
} as const satisfies Record<SignatureInkColor, string>;

export function signatureInkColorCss(
  inkColor: SignatureInkColor,
): "#000000" | "#FFFFFF" {
  return SIGNATURE_INK_CSS[inkColor];
}

export function calculateDisplayLineWidth(
  width: number,
  height: number,
  density: number,
): number {
  return Math.max(calculatePreviewLineWidth(width, height), 2 / density);
}

export const SignatureGeometry = memo(function SignatureGeometry({
  inkColor,
  signature,
}: SignatureGeometryProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const redraw = useCallback(() => {
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d");
    if (
      canvas === null ||
      canvas === undefined ||
      context === null ||
      context === undefined
    )
      return;
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    const density = Math.max(1, window.devicePixelRatio);
    canvas.width = Math.round(width * density);
    canvas.height = Math.round(height * density);
    context.setTransform(density, 0, 0, density, 0, 0);
    context.clearRect(0, 0, width, height);
    context.strokeStyle = signatureInkColorCss(inkColor);
    context.lineCap = "round";
    context.lineJoin = "round";
    context.lineWidth = calculateDisplayLineWidth(width, height, density);
    for (const stroke of signature.strokes)
      drawStroke({ context, height, width }, stroke);
  }, [inkColor, signature]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (canvas === null) return;
    const observer = new ResizeObserver(redraw);
    observer.observe(canvas);
    redraw();
    return () => observer.disconnect();
  }, [redraw]);

  return (
    <canvas
      aria-label="제출된 서명"
      className="full-view-signature"
      data-testid="submitted-signature"
      ref={canvasRef}
      role="img"
    />
  );
});
