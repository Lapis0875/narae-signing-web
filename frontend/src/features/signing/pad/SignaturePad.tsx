import {
  type PointerEvent as ReactPointerEvent,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import { calculatePreviewLineWidth, drawStroke } from "./canvasRenderer.ts";
import {
  appendPoint,
  createPayload,
  MAX_POINTS,
  MAX_STROKES,
  normalizePoint,
  type SignaturePayload,
  type SignaturePoint,
  type SignatureStroke,
} from "./signaturePayload.ts";
import "./SignaturePad.css";

type SignaturePadProps = {
  readonly onSubmit: (payload: SignaturePayload) => Promise<boolean>;
};

type ActiveStroke = {
  readonly pointerId: number;
  readonly points: SignaturePoint[];
};

export function SignaturePad({ onSubmit }: SignaturePadProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const strokesRef = useRef<SignatureStroke[]>([]);
  const activeStrokeRef = useRef<ActiveStroke | null>(null);
  const pointCountRef = useRef(0);
  const [revision, setRevision] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [status, setStatus] = useState("");

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
  }, []);

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
      const context = canvas.getContext("2d");
      context?.setTransform(dpr, 0, 0, dpr, 0, 0);
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

  const appendEventPoint = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    const activeStroke = activeStrokeRef.current;
    if (pointCountRef.current >= MAX_POINTS) {
      setStatus("서명 데이터가 허용 범위를 초과했습니다.");
      return;
    }
    if (activeStroke === null || activeStroke.pointerId !== event.pointerId) {
      return;
    }
    const point = normalizePoint(
      event,
      event.currentTarget.getBoundingClientRect(),
    );
    if (appendPoint(activeStroke.points, point)) {
      pointCountRef.current += 1;
      redraw();
    }
  };

  const cancelActiveStroke = (pointerId: number) => {
    const activeStroke = activeStrokeRef.current;
    if (activeStroke === null || activeStroke.pointerId !== pointerId) {
      return;
    }
    pointCountRef.current -= activeStroke.points.length;
    activeStrokeRef.current = null;
    redraw();
  };

  const handlePointerDown = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    if (
      strokesRef.current.length >= MAX_STROKES ||
      pointCountRef.current >= MAX_POINTS
    ) {
      setStatus("서명 데이터가 허용 범위를 초과했습니다.");
      return;
    }
    if (
      submitting ||
      !event.isPrimary ||
      event.button !== 0 ||
      activeStrokeRef.current !== null
    ) {
      return;
    }
    event.preventDefault();
    activeStrokeRef.current = { pointerId: event.pointerId, points: [] };
    event.currentTarget.setPointerCapture(event.pointerId);
    appendEventPoint(event);
  };

  const handlePointerUp = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    const activeStroke = activeStrokeRef.current;
    if (activeStroke === null || activeStroke.pointerId !== event.pointerId) {
      return;
    }
    appendEventPoint(event);
    strokesRef.current.push({ points: [...activeStroke.points] });
    activeStrokeRef.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    setRevision((value) => value + 1);
    redraw();
  };

  const clearStrokes = () => {
    strokesRef.current = [];
    activeStrokeRef.current = null;
    pointCountRef.current = 0;
    setRevision((value) => value + 1);
    redraw();
  };

  const clearByUser = () => {
    clearStrokes();
    setStatus("서명을 모두 지웠습니다.");
  };

  const submit = async () => {
    const payload = createPayload(strokesRef.current);
    if (payload === null) {
      setStatus("서명 데이터가 허용 범위를 초과했습니다.");
      return;
    }
    setSubmitting(true);
    setStatus("");
    const saved = await Promise.resolve()
      .then(() => onSubmit(payload))
      .then(
        (result) => result,
        () => false,
      );
    if (saved) {
      clearStrokes();
      setStatus("서명이 저장되었습니다.");
    } else {
      setStatus("서명을 저장하지 못했습니다. 다시 시도해 주세요.");
    }
    setSubmitting(false);
  };

  return (
    <section
      aria-label="서명 입력"
      className="app-panel route-panel signature-pad"
    >
      <canvas
        aria-label="서명 패드"
        data-point-count={pointCountRef.current}
        data-stroke-count={strokesRef.current.length}
        data-testid="signer-canvas"
        onLostPointerCapture={(event) => cancelActiveStroke(event.pointerId)}
        onPointerCancel={(event) => cancelActiveStroke(event.pointerId)}
        onPointerDown={handlePointerDown}
        onPointerMove={appendEventPoint}
        onPointerUp={handlePointerUp}
        ref={canvasRef}
        role="img"
        className="signature-pad__canvas"
      />
      <div className="signature-pad__controls">
        <button
          className="app-primary-button signature-pad__action"
          disabled={strokesRef.current.length === 0 || submitting}
          onClick={clearByUser}
          type="button"
        >
          모두 지우기
        </button>
        <button
          className="app-primary-button signature-pad__action"
          disabled={strokesRef.current.length === 0 || submitting}
          onClick={submit}
          type="button"
        >
          {submitting ? "저장 중" : "서명 제출"}
        </button>
      </div>
      <p
        aria-live="polite"
        className="signature-pad__status"
        data-testid="signature-status"
      >
        {status}
      </p>
      <output hidden>{revision}</output>
    </section>
  );
}
