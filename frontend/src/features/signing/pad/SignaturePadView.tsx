import type {
  PointerEventHandler,
  RefObject,
} from "react";

type SignaturePadViewProps = {
  readonly canvasRef: RefObject<HTMLCanvasElement | null>;
  readonly clearing: boolean;
  readonly onCancel: () => void;
  readonly onClear: () => void;
  readonly onLostPointerCapture: PointerEventHandler<HTMLCanvasElement>;
  readonly onPointerCancel: PointerEventHandler<HTMLCanvasElement>;
  readonly onPointerDown: PointerEventHandler<HTMLCanvasElement>;
  readonly onPointerMove: PointerEventHandler<HTMLCanvasElement>;
  readonly onPointerUp: PointerEventHandler<HTMLCanvasElement>;
  readonly onSubmit: () => void;
  readonly pointCount: number;
  readonly revision: number;
  readonly showCancel: boolean;
  readonly status: string;
  readonly strokeCount: number;
  readonly submitting: boolean;
};

export function SignaturePadView({
  canvasRef,
  clearing,
  onCancel,
  onClear,
  onLostPointerCapture,
  onPointerCancel,
  onPointerDown,
  onPointerMove,
  onPointerUp,
  onSubmit,
  pointCount,
  revision,
  showCancel,
  status,
  strokeCount,
  submitting,
}: SignaturePadViewProps) {
  const busy = submitting || clearing;
  return (
    <section aria-label="서명 입력" className="app-panel route-panel signature-pad">
      <canvas
        aria-label="서명 패드"
        className="signature-pad__canvas"
        data-point-count={pointCount}
        data-stroke-count={strokeCount}
        data-testid="signer-canvas"
        onLostPointerCapture={onLostPointerCapture}
        onPointerCancel={onPointerCancel}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        ref={canvasRef}
        role="img"
      />
      <div className="signature-pad__controls">
        {showCancel ? (
          <button
            className="app-primary-button signature-pad__action"
            disabled={busy}
            onClick={onCancel}
            type="button"
          >
            서명 취소
          </button>
        ) : null}
        <button
          className="app-primary-button signature-pad__action"
          disabled={strokeCount === 0 || busy}
          onClick={onClear}
          type="button"
        >
          모두 지우기
        </button>
        <button
          className="app-primary-button signature-pad__action"
          disabled={strokeCount === 0 || busy}
          onClick={onSubmit}
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
