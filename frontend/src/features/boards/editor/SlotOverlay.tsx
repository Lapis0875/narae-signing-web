import { Maximize2, X } from "lucide-react";
import type { KeyboardEvent, PointerEvent } from "react";
import type { RosterEntry } from "../roster/rosterApi.ts";
import type { Bounds } from "./geometry.ts";

type SlotOverlayProps = {
  readonly bounds: Bounds;
  readonly colliding: boolean;
  readonly entry: RosterEntry;
  readonly onKeyboardMove: (x: number, y: number) => void;
  readonly onKeyboardResize: (width: number, height: number) => void;
  readonly onPointerStart: (
    event: PointerEvent<HTMLButtonElement>,
    mode: "move" | "resize",
  ) => void;
  readonly onUnplace: () => void;
};

export function SlotOverlay({
  bounds,
  colliding,
  entry,
  onKeyboardMove,
  onKeyboardResize,
  onPointerStart,
  onUnplace,
}: SlotOverlayProps) {
  const labelCharacters = Array.from(entry.identity.name);
  const labelPrefix = labelCharacters.slice(0, -1).join("");
  const labelSuffix = labelCharacters.at(-1) ?? "";
  const keyboardMove = (event: KeyboardEvent<HTMLButtonElement>) => {
    const step = event.shiftKey ? 0.05 : 0.01;
    const moves: Record<string, readonly [number, number]> = {
      ArrowDown: [0, step],
      ArrowLeft: [-step, 0],
      ArrowRight: [step, 0],
      ArrowUp: [0, -step],
    };
    const move = moves[event.key];
    if (move !== undefined) {
      event.preventDefault();
      onKeyboardMove(move[0], move[1]);
    }
  };

  return (
    <div
      className={`slot-overlay${colliding ? " slot-overlay--collision" : ""}`}
      data-slot-id={entry.slot.id}
      data-testid={`slot-${entry.slot.id}`}
      style={{
        height: `${bounds.height * 100}%`,
        left: `${bounds.x * 100}%`,
        top: `${bounds.y * 100}%`,
        width: `${bounds.width * 100}%`,
      }}
    >
      <button
        aria-label={`${entry.identity.name} 이동`}
        className="board-button slot-move"
        onKeyDown={keyboardMove}
        onPointerDown={(event) => onPointerStart(event, "move")}
        type="button"
      >
        <span className="slot-label">
          <span className="slot-label-prefix">{labelPrefix}</span>
          <span className="slot-label-suffix">{labelSuffix}</span>
        </span>
      </button>
      <div className="slot-actions">
        <button
          aria-label={`${entry.identity.name} 배치 해제`}
          className="board-button board-button--destructive"
          onClick={(event) => {
            event.stopPropagation();
            onUnplace();
          }}
          type="button"
        >
          <X aria-hidden="true" />
        </button>
      </div>
      <button
        aria-label={`${entry.identity.name} 크기 조절`}
        className="board-button board-button--primary slot-resize"
        onKeyDown={(event) => {
          const step = event.shiftKey ? 0.05 : 0.01;
          if (event.key === "ArrowRight" || event.key === "ArrowDown") {
            event.preventDefault();
            onKeyboardResize(
              event.key === "ArrowRight" ? step : 0,
              event.key === "ArrowDown" ? step : 0,
            );
          }
          if (event.key === "ArrowLeft" || event.key === "ArrowUp") {
            event.preventDefault();
            onKeyboardResize(
              event.key === "ArrowLeft" ? -step : 0,
              event.key === "ArrowUp" ? -step : 0,
            );
          }
        }}
        onPointerDown={(event) => {
          event.stopPropagation();
          onPointerStart(event, "resize");
        }}
        type="button"
      >
        <Maximize2 aria-hidden="true" />
      </button>
    </div>
  );
}
