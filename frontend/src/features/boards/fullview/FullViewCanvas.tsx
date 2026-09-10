import { memo } from "react";
import type { FullViewSlot, FullViewSnapshot } from "./fullViewApi.ts";
import { SignatureGeometry } from "./SignatureGeometry.tsx";
import "./fullView.css";

type FullViewCanvasProps = {
  readonly backgroundUrl: string | null;
  readonly snapshot: FullViewSnapshot;
};

type FullViewSlotGeometryProps = {
  readonly inkColor: FullViewSnapshot["signatureInkColor"];
  readonly slot: FullViewSlot;
};

const FullViewSlotGeometry = memo(function FullViewSlotGeometry({
  inkColor,
  slot,
}: FullViewSlotGeometryProps) {
  const signature = slot.draftSignature ?? slot.signature;
  return (
    <div
      className="full-view-slot"
      data-slot-id={slot.id}
      style={{
        height: `${slot.height * 100}%`,
        left: `${slot.x * 100}%`,
        top: `${slot.y * 100}%`,
        width: `${slot.width * 100}%`,
      }}
    >
      {signature === null ? null : (
        <SignatureGeometry inkColor={inkColor} signature={signature} />
      )}
    </div>
  );
});

export function FullViewCanvas({
  backgroundUrl,
  snapshot,
}: FullViewCanvasProps) {
  return (
    <div
      aria-label="서명 보드 전체보기"
      className="full-view-canvas"
      data-testid="full-view-canvas"
      role="img"
      style={{
        aspectRatio: `${snapshot.canvasWidth} / ${snapshot.canvasHeight}`,
      }}
    >
      <div
        className="full-view-frame"
        style={{
          aspectRatio: `${snapshot.canvasWidth} / ${snapshot.canvasHeight}`,
        }}
      >
        {backgroundUrl === null ? null : (
          <img alt="" className="full-view-background" src={backgroundUrl} />
        )}
        {snapshot.slots.map((slot) => (
          <FullViewSlotGeometry
            inkColor={snapshot.signatureInkColor}
            key={slot.id}
            slot={slot}
          />
        ))}
      </div>
    </div>
  );
}
