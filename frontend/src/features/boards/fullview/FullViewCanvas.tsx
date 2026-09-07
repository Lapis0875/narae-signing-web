import { memo } from "react"
import type { FullViewSlot, FullViewSnapshot } from "./fullViewApi.ts"
import { SignatureGeometry } from "./SignatureGeometry.tsx"
import "./fullView.css"

type FullViewCanvasProps = {
  readonly backgroundUrl: string | null
  readonly snapshot: FullViewSnapshot
}

const FullViewSlotGeometry = memo(function FullViewSlotGeometry({ slot }: { readonly slot: FullViewSlot }) {
  const signature = slot.draftSignature ?? slot.signature
  return (
    <div
      className="full-view-slot"
      data-background={slot.background.toLowerCase()}
      data-slot-id={slot.id}
      style={{ height: `${slot.height * 100}%`, left: `${slot.x * 100}%`, top: `${slot.y * 100}%`, width: `${slot.width * 100}%` }}
    >
      {signature === null ? null : <SignatureGeometry signature={signature} />}
    </div>
  )
})

export function FullViewCanvas({ backgroundUrl, snapshot }: FullViewCanvasProps) {
  return (
    <div
      aria-label="서명 보드 전체보기"
      className="full-view-canvas"
      data-testid="full-view-canvas"
      role="img"
      style={{ aspectRatio: `${snapshot.canvasWidth} / ${snapshot.canvasHeight}` }}
    >
      {backgroundUrl === null ? null : <img alt="" className="full-view-background" src={backgroundUrl} />}
      {snapshot.slots.map((slot) => <FullViewSlotGeometry key={slot.id} slot={slot} />)}
    </div>
  )
}
