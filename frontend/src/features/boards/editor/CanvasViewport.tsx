import {
  type DragEvent,
  type PointerEvent,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import type { RosterEntry } from "../roster/rosterApi.ts";
import { type Bounds, clampBounds, collides } from "./geometry.ts";
import { SlotOverlay } from "./SlotOverlay.tsx";

type CanvasViewportProps = {
  readonly backgroundUrl: string | null;
  readonly canvasHeight: number;
  readonly canvasWidth: number;
  readonly entries: readonly RosterEntry[];
  readonly onSave: (entry: RosterEntry, bounds: Bounds) => void;
  readonly onUnplace: (entry: RosterEntry) => void;
};
type Gesture = {
  readonly bounds: Bounds;
  readonly clientX: number;
  readonly clientY: number;
  readonly mode: "move" | "resize";
  readonly slotId: string;
};

function entryBounds(entry: RosterEntry): Bounds | null {
  const { height, width, x, y } = entry.slot;
  return height === null || width === null || x === null || y === null
    ? null
    : { height, width, x, y };
}

export function CanvasViewport({
  backgroundUrl,
  canvasHeight,
  canvasWidth,
  entries,
  onSave,
  onUnplace,
}: CanvasViewportProps) {
  const canvasRef = useRef<HTMLDivElement>(null);
  const [drafts, setDrafts] = useState<Record<string, Bounds>>({});
  const [gesture, setGesture] = useState<Gesture | null>(null);
  useEffect(() => {
    const snapshot: Record<string, Bounds> = {};
    for (const entry of entries) {
      const bounds = entryBounds(entry);
      if (bounds !== null) snapshot[entry.slot.id] = bounds;
    }
    setDrafts(snapshot);
  }, [entries]);

  const placed = useMemo(
    () => entries.filter((entry) => entryBounds(entry) !== null),
    [entries],
  );
  const collisionFor = (slotId: string, bounds: Bounds) =>
    placed.some((entry) => {
      const other = drafts[entry.slot.id] ?? entryBounds(entry);
      return (
        entry.slot.id !== slotId && other !== null && collides(bounds, other)
      );
    });
  const saveDraft = (entry: RosterEntry, bounds: Bounds) => {
    onSave(entry, bounds);
  };
  const moveDraft = (event: PointerEvent<HTMLDivElement>) => {
    if (gesture === null || canvasRef.current === null) return;
    const rect = canvasRef.current.getBoundingClientRect();
    const dx = (event.clientX - gesture.clientX) / rect.width;
    const dy = (event.clientY - gesture.clientY) / rect.height;
    const changed =
      gesture.mode === "move"
        ? {
            ...gesture.bounds,
            x: gesture.bounds.x + dx,
            y: gesture.bounds.y + dy,
          }
        : {
            ...gesture.bounds,
            height: gesture.bounds.height + dy,
            width: gesture.bounds.width + dx,
          };
    setDrafts((current) => ({
      ...current,
      [gesture.slotId]: clampBounds(changed),
    }));
  };
  const finishGesture = () => {
    if (gesture !== null) {
      const entry = entries.find(
        (candidate) => candidate.slot.id === gesture.slotId,
      );
      const bounds = drafts[gesture.slotId];
      if (entry !== undefined && bounds !== undefined) saveDraft(entry, bounds);
    }
    setGesture(null);
  };
  const drop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    const entry = entries.find(
      (candidate) =>
        candidate.slot.id === event.dataTransfer.getData("text/slot-id"),
    );
    const rect = canvasRef.current?.getBoundingClientRect();
    if (entry === undefined || rect === undefined) return;
    const bounds = clampBounds({
      height: 0.18,
      width: 0.24,
      x: (event.clientX - rect.left) / rect.width,
      y: (event.clientY - rect.top) / rect.height,
    });
    setDrafts((current) => ({ ...current, [entry.slot.id]: bounds }));
    saveDraft(entry, bounds);
  };

  return (
    <div
      aria-label="서명 보드 캔버스"
      className="editor-canvas"
      onDragOver={(event) => event.preventDefault()}
      onDrop={drop}
      onPointerMove={moveDraft}
      onPointerUp={finishGesture}
      ref={canvasRef}
      role="application"
      style={{ aspectRatio: `${canvasWidth} / ${canvasHeight}` }}
    >
      {backgroundUrl === null ? null : (
        <img alt="" className="editor-canvas-background" src={backgroundUrl} />
      )}
      {placed.map((entry) => {
        const bounds = drafts[entry.slot.id] ?? entryBounds(entry);
        if (bounds === null) return null;
        return (
          <SlotOverlay
            bounds={bounds}
            colliding={collisionFor(entry.slot.id, bounds)}
            entry={entry}
            key={entry.id}
            onKeyboardMove={(x, y) => {
              const changed = clampBounds({
                ...bounds,
                x: bounds.x + x,
                y: bounds.y + y,
              });
              setDrafts((current) => ({
                ...current,
                [entry.slot.id]: changed,
              }));
              saveDraft(entry, changed);
            }}
            onKeyboardResize={(width, height) => {
              const changed = clampBounds({
                ...bounds,
                height: bounds.height + height,
                width: bounds.width + width,
              });
              setDrafts((current) => ({
                ...current,
                [entry.slot.id]: changed,
              }));
              saveDraft(entry, changed);
            }}
            onPointerStart={(event, mode) => {
              event.currentTarget.setPointerCapture(event.pointerId);
              setGesture({
                bounds,
                clientX: event.clientX,
                clientY: event.clientY,
                mode,
                slotId: entry.slot.id,
              });
            }}
            onUnplace={() => onUnplace(entry)}
          />
        );
      })}
    </div>
  );
}
