import {
  type PointerEvent as ReactPointerEvent,
  useCallback,
  useRef,
  useState,
} from "react";
import type {
  SignatureDraftDelta,
  SignatureDraftVersion,
} from "../api/signatureDraftApi.ts";
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
import { useSignatureDraftSync } from "./useSignatureDraftSync.ts";
import { type ActiveStroke, useSignatureCanvas } from "./useSignatureCanvas.ts";
import { SignaturePadView } from "./SignaturePadView.tsx";
import "./SignaturePad.css";

type SignaturePadProps = {
  readonly onCancel?: () => Promise<boolean>;
  readonly onClearDraft?: () => Promise<boolean>;
  readonly onDraft?: (
    payload: SignaturePayload,
  ) => Promise<SignatureDraftVersion | null>;
  readonly onDraftDelta?: (
    delta: SignatureDraftDelta,
  ) => Promise<SignatureDraftVersion | null>;
  readonly onSubmit: (payload: SignaturePayload) => Promise<boolean>;
};

export function SignaturePad({
  onCancel,
  onClearDraft,
  onDraft,
  onDraftDelta,
  onSubmit,
}: SignaturePadProps) {
  const strokesRef = useRef<SignatureStroke[]>([]);
  const activeStrokeRef = useRef<ActiveStroke | null>(null);
  const pointCountRef = useRef(0);
  const draftStoppingRef = useRef(false);
  const [revision, setRevision] = useState(0);
  const [clearing, setClearing] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [status, setStatus] = useState("");

  const { canvasRef, redraw } = useSignatureCanvas(strokesRef, activeStrokeRef);

  const draftPayload = useCallback((): SignaturePayload | null => {
    const activeStroke = activeStrokeRef.current;
    const strokes = activeStroke === null || activeStroke.points.length === 0
      ? strokesRef.current
      : [...strokesRef.current, { points: [...activeStroke.points] }];
    return createPayload(strokes);
  }, []);

  const {
    queue: queueDraft,
    replace: replaceDraft,
    resumeAfterClear: resumeDraftAfterClear,
    stop: stopDraftSync,
  } = useSignatureDraftSync({
    payload: draftPayload,
    readyForFull: useCallback(() => activeStrokeRef.current === null, []),
    sendDelta: onDraftDelta,
    sendFull: onDraft,
    setFailure: useCallback(
      () => setStatus("서명 진행 상태를 동기화하지 못했습니다."),
      [],
    ),
    stopping: draftStoppingRef,
  });

  const appendEventPoints = (
    event: ReactPointerEvent<HTMLCanvasElement>,
    operation: "append" | "begin",
  ) => {
    const activeStroke = activeStrokeRef.current;
    if (activeStroke === null || activeStroke.pointerId !== event.pointerId) {
      return;
    }
    const coalesced = event.nativeEvent.getCoalescedEvents?.() ?? [];
    const samples = coalesced.length === 0 ? [event] : coalesced;
    const points: SignaturePoint[] = [];
    const bounds = event.currentTarget.getBoundingClientRect();
    for (const sample of samples) {
      if (pointCountRef.current >= MAX_POINTS) {
        setStatus("서명 데이터가 허용 범위를 초과했습니다.");
        break;
      }
      const point = normalizePoint(sample, bounds);
      if (appendPoint(activeStroke.points, point)) {
        pointCountRef.current += 1;
        points.push(point);
      }
    }
    if (points.length > 0) {
      redraw();
      queueDraft({ operation, points, strokeIndex: strokesRef.current.length });
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
    replaceDraft();
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
    appendEventPoints(event, "begin");
  };

  const handlePointerUp = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    const activeStroke = activeStrokeRef.current;
    if (activeStroke === null || activeStroke.pointerId !== event.pointerId) {
      return;
    }
    appendEventPoints(event, "append");
    strokesRef.current.push({ points: [...activeStroke.points] });
    activeStrokeRef.current = null;
    queueDraft({
      operation: "end",
      points: [],
      strokeIndex: strokesRef.current.length - 1,
    }, true);
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
      resumeDraftAfterClear();
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
        if (!cancelled) {
          setStatus("서명을 취소하지 못했습니다.");
          resumeDraftAfterClear();
        }
      },
      () => {
        setStatus("서명을 취소하지 못했습니다.");
        resumeDraftAfterClear();
      },
    ).finally(() => {
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

  return <SignaturePadView
    canvasRef={canvasRef}
    clearing={clearing}
    onCancel={cancelSigning}
    onClear={clearByUser}
    onLostPointerCapture={(event) => cancelActiveStroke(event.pointerId)}
    onPointerCancel={(event) => cancelActiveStroke(event.pointerId)}
    onPointerDown={handlePointerDown}
    onPointerMove={(event) => appendEventPoints(event, "append")}
    onPointerUp={handlePointerUp}
    onSubmit={() => void submit()}
    pointCount={pointCountRef.current}
    revision={revision}
    showCancel={onCancel !== undefined}
    status={status}
    strokeCount={strokesRef.current.length}
    submitting={submitting}
  />;
}
