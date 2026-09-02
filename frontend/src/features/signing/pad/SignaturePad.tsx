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
  readonly onCancel?: () => Promise<boolean>;
  readonly onClearDraft?: () => Promise<boolean>;
  readonly onDraft?: (payload: SignaturePayload) => Promise<boolean>;
  readonly onSubmit: (payload: SignaturePayload) => Promise<boolean>;
};

type ActiveStroke = {
  readonly pointerId: number;
  readonly points: SignaturePoint[];
};

export function SignaturePad({
  onCancel,
  onClearDraft,
  onDraft,
  onSubmit,
}: SignaturePadProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const strokesRef = useRef<SignatureStroke[]>([]);
  const activeStrokeRef = useRef<ActiveStroke | null>(null);
  const pointCountRef = useRef(0);
  const draftTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const draftRequestRef = useRef<Promise<void> | null>(null);
  const draftSendingRef = useRef(false);
  const draftQueuedRef = useRef(false);
  const draftStoppingRef = useRef(false);
  const [revision, setRevision] = useState(0);
  const [clearing, setClearing] = useState(false);
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

  const draftPayload = useCallback((): SignaturePayload | null => {
    const activeStroke = activeStrokeRef.current;
    const strokes = activeStroke === null || activeStroke.points.length === 0
      ? strokesRef.current
      : [...strokesRef.current, { points: [...activeStroke.points] }];
    return createPayload(strokes);
  }, []);

  const publishDraft = useCallback(() => {
    if (onDraft === undefined || submitting || draftStoppingRef.current) {
      return;
    }
    if (draftSendingRef.current) {
      draftQueuedRef.current = true;
      return;
    }
    const payload = draftPayload();
    if (payload === null || payload.strokes.length === 0) {
      return;
    }
    draftSendingRef.current = true;
    const request = Promise.resolve()
      .then(() => onDraft(payload))
      .then(
        (saved) => {
          if (!saved) {
            setStatus("서명 진행 상태를 동기화하지 못했습니다.");
          }
        },
        () => setStatus("서명 진행 상태를 동기화하지 못했습니다."),
      )
      .then(() => {
        draftSendingRef.current = false;
        if (draftQueuedRef.current) {
          draftQueuedRef.current = false;
          queueMicrotask(publishDraft);
        }
      });
    draftRequestRef.current = request;
    void request;
  }, [draftPayload, onDraft, submitting]);

  const scheduleDraft = useCallback((delay = 300) => {
    if (onDraft === undefined) {
      return;
    }
    if (draftTimerRef.current !== null) {
      clearTimeout(draftTimerRef.current);
    }
    draftTimerRef.current = setTimeout(() => {
      draftTimerRef.current = null;
      publishDraft();
    }, delay);
  }, [onDraft, publishDraft]);

  useEffect(() => () => {
    if (draftTimerRef.current !== null) {
      clearTimeout(draftTimerRef.current);
    }
  }, []);

  useEffect(() => {
    if (onDraft === undefined) {
      return;
    }
    const heartbeat = setInterval(publishDraft, 20_000);
    return () => clearInterval(heartbeat);
  }, [onDraft, publishDraft]);

  const stopDraftSync = useCallback(async () => {
    draftStoppingRef.current = true;
    if (draftTimerRef.current !== null) {
      clearTimeout(draftTimerRef.current);
      draftTimerRef.current = null;
    }
    draftQueuedRef.current = false;
    await draftRequestRef.current;
  }, []);

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
      scheduleDraft();
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
    scheduleDraft(0);
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
      clearing ||
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
    scheduleDraft(0);
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
    if (onClearDraft === undefined) {
      setStatus("서명을 모두 지웠습니다.");
      return;
    }
    setClearing(true);
    void Promise.resolve().then(async () => {
      await stopDraftSync();
      return onClearDraft();
    }).then(
      (cleared) => setStatus(cleared ? "서명을 모두 지웠습니다." : "서명을 지우지 못했습니다."),
      () => setStatus("서명을 지우지 못했습니다."),
    ).finally(() => {
      draftStoppingRef.current = false;
      setClearing(false);
    });
  };

  const cancelSigning = () => {
    if (onCancel === undefined || submitting || clearing) {
      return;
    }
    clearStrokes();
    setClearing(true);
    void Promise.resolve().then(async () => {
      await stopDraftSync();
      return onCancel();
    }).then(
      (cancelled) => {
        if (!cancelled) setStatus("서명을 취소하지 못했습니다.");
      },
      () => setStatus("서명을 취소하지 못했습니다."),
    ).finally(() => {
      draftStoppingRef.current = false;
      setClearing(false);
    });
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
        {onCancel === undefined ? null : (
          <button
            className="app-primary-button signature-pad__action"
            disabled={submitting || clearing}
            onClick={cancelSigning}
            type="button"
          >
            서명 취소
          </button>
        )}
        <button
          className="app-primary-button signature-pad__action"
          disabled={strokesRef.current.length === 0 || submitting || clearing}
          onClick={clearByUser}
          type="button"
        >
          모두 지우기
        </button>
        <button
          className="app-primary-button signature-pad__action"
          disabled={strokesRef.current.length === 0 || submitting || clearing}
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
