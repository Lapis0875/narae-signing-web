import { useCallback, useEffect, useRef, type RefObject } from "react";
import { calculatePreviewLineWidth, drawStroke } from "./canvasRenderer.ts";
import type { SignaturePoint, SignatureStroke } from "./signaturePayload.ts";

export type ActiveStroke = {
  readonly pointerId: number;
  readonly points: SignaturePoint[];
};

export function useSignatureCanvas(
  strokesRef: RefObject<readonly SignatureStroke[]>,
  activeStrokeRef: RefObject<ActiveStroke | null>,
) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const redraw = useCallback(() => {
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d");
    if (
      canvas === null ||
      canvas === undefined ||
      context === null ||
      context === undefined
    ) {
      return;
    }
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    context.clearRect(0, 0, width, height);
    context.strokeStyle = getComputedStyle(canvas)
      .getPropertyValue("--signature-pad-ink")
      .trim();
    context.lineCap = "round";
    context.lineJoin = "round";
    context.lineWidth = calculatePreviewLineWidth(width, height);
    const surface = { context, height, width };
    for (const stroke of strokesRef.current) {
      drawStroke(surface, stroke);
    }
    const activeStroke = activeStrokeRef.current;
    if (activeStroke !== null) {
      drawStroke(surface, activeStroke);
    }
  }, [activeStrokeRef, strokesRef]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (canvas === null) {
      return;
    }
    const resize = () => {
      const cssWidth = canvas.clientWidth;
      const cssHeight = canvas.clientHeight;
      const dpr = Math.max(1, window.devicePixelRatio);
      const width = Math.round(cssWidth * dpr);
      const height = Math.round(cssHeight * dpr);
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
      }
      canvas.getContext("2d")?.setTransform(dpr, 0, 0, dpr, 0, 0);
      redraw();
    };
    const observer = new ResizeObserver(resize);
    observer.observe(canvas);
    window.addEventListener("resize", resize);
    resize();
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", resize);
    };
  }, [redraw]);

  return { canvasRef, redraw };
}
