import { useEffect } from "react"
import { z } from "zod"
import { pointSchema, signatureSchema, type FullViewSlot, type FullViewSnapshot } from "./fullViewApi.ts"

const EVENT_TYPES = [
  "background-updated", "board-deleted", "board-state-updated", "board-updated", "layout-updated",
  "signature-reset", "signature-submitted", "signature-draft", "signature-draft-cleared",
] as const

const incrementalEventSchema = z.discriminatedUnion("operation", [
  z.strictObject({
    draftEpoch: z.number().int().nonnegative(),
    operation: z.enum(["begin", "append"]),
    points: z.array(pointSchema).min(1),
    revision: z.number().int().positive(),
    slotId: z.uuid(),
    strokeIndex: z.number().int().nonnegative().max(127),
  }),
  z.strictObject({
    draftEpoch: z.number().int().nonnegative(),
    operation: z.literal("end"),
    points: z.array(pointSchema).length(0),
    revision: z.number().int().positive(),
    slotId: z.uuid(),
    strokeIndex: z.number().int().nonnegative().max(127),
  }),
  z.strictObject({
    draftEpoch: z.number().int().positive(),
    operation: z.literal("clear"),
    points: z.array(pointSchema).length(0),
    revision: z.literal(0),
    signature: signatureSchema.nullable(),
    slotId: z.uuid(),
    strokeIndex: z.literal(-1),
  }),
  z.strictObject({
    draftEpoch: z.number().int().positive(), operation: z.literal("full-reset"),
    points: z.array(pointSchema).length(0), revision: z.literal(0), signature: signatureSchema,
    slotId: z.uuid(), strokeIndex: z.literal(-1),
  }),
])
export type PublicDraftEvent = z.infer<typeof incrementalEventSchema>
export type DraftReconciliation = { readonly kind: "applied"; readonly snapshot: FullViewSnapshot }
  | { readonly kind: "ignored" } | { readonly kind: "recover" }
export function parsePublicDraftEvent(data: string): PublicDraftEvent {
  return incrementalEventSchema.parse(JSON.parse(data))
}
function compareVersion(
  left: Pick<FullViewSlot, "draftEpoch" | "revision">,
  right: Pick<FullViewSlot, "draftEpoch" | "revision">,
): number {
  return left.draftEpoch === right.draftEpoch ? left.revision - right.revision : left.draftEpoch - right.draftEpoch
}
function updateIncrementalSlot(slot: FullViewSlot, event: PublicDraftEvent): FullViewSlot | null {
  switch (event.operation) {
    case "begin": {
      const signature = slot.draftSignature
      if (signature === null || event.strokeIndex !== signature.strokes.length) return null
      return {
        ...slot,
        draftSignature: { ...signature, strokes: [...signature.strokes, { points: event.points }] },
        revision: event.revision,
      }
    }
    case "append": {
      const signature = slot.draftSignature
      if (signature === null || signature.strokes[event.strokeIndex] === undefined) return null
      return {
        ...slot,
        draftSignature: {
          ...signature,
          strokes: signature.strokes.map((stroke, index) => index === event.strokeIndex
            ? { points: [...stroke.points, ...event.points] }
            : stroke),
        },
        revision: event.revision,
      }
    }
    case "end":
      if (slot.draftSignature?.strokes[event.strokeIndex] === undefined) return null
      return { ...slot, revision: event.revision }
    case "clear":
    case "full-reset":
      return { ...slot, draftEpoch: event.draftEpoch, draftSignature: event.signature, revision: 0 }
  }
}

export function reconcilePublicDraft(snapshot: FullViewSnapshot, event: PublicDraftEvent): DraftReconciliation {
  const slotIndex = snapshot.slots.findIndex(({ id }) => id === event.slotId)
  const slot = snapshot.slots[slotIndex]
  if (slot === undefined) return { kind: "recover" }
  if (compareVersion(event, slot) <= 0) return { kind: "ignored" }
  const reset = event.operation === "clear" || event.operation === "full-reset"
  const next = reset
    ? event.draftEpoch === slot.draftEpoch + 1 && event.revision === 0
    : event.draftEpoch === slot.draftEpoch && event.revision === slot.revision + 1
  if (!next) return { kind: "recover" }
  const updated = updateIncrementalSlot(slot, event)
  if (updated === null) return { kind: "recover" }
  return { kind: "applied", snapshot: {
    ...snapshot, slots: snapshot.slots.map((current, index) => index === slotIndex ? updated : current),
  } }
}

export function mergePublicSnapshot(local: FullViewSnapshot, incoming: FullViewSnapshot): FullViewSnapshot {
  if (local.boardId !== incoming.boardId) return incoming
  const localSlots = new Map(local.slots.map((slot) => [slot.id, slot]))
  return {
    ...incoming,
    slots: incoming.slots.map((slot) => {
      const current = localSlots.get(slot.id)
      return current === undefined || compareVersion(slot, current) >= 0
        ? slot
        : {
            ...slot,
            draftEpoch: current.draftEpoch,
            draftSignature: current.draftSignature,
            revision: current.revision,
            signature: current.signature,
          }
    }),
  }
}

export function reconnectDelay(attempt: number): number {
  return Math.min(4_000, 250 * 2 ** attempt)
}

function useRealtime(
  eventUrl: string,
  refetchSnapshot: () => Promise<unknown>,
  refetchBackground?: () => Promise<unknown>,
): void {
  useEffect(() => {
    if (typeof EventSource === "undefined") return
    let active = true
    let attempt = 0
    let source: EventSource | null = null
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null
    const coalesce = (fetch: () => Promise<unknown>) => {
      let refresh: Promise<unknown> | null = null
      let refreshQueued = false
      let pending = false
      const runRefetch = () => {
        refreshQueued = false
        if (!active) return
        pending = false
        refresh = fetch().finally(() => {
          refresh = null
          if (pending) refetch()
        })
      }
      const refetch = () => {
        pending = true
        if (refresh !== null || refreshQueued) return
        refreshQueued = true
        queueMicrotask(runRefetch)
      }
      return refetch
    }
    const refreshSnapshot = coalesce(refetchSnapshot)
    const refreshBackground = refetchBackground === undefined ? undefined : coalesce(refetchBackground)
    const connect = () => {
      if (!active) return
      source = new EventSource(eventUrl)
      source.onopen = () => { attempt = 0; refreshSnapshot(); refreshBackground?.() }
      for (const type of EVENT_TYPES) source.addEventListener(type, () => {
        refreshSnapshot()
        if (!type.startsWith("signature-")) refreshBackground?.()
      })
      source.onerror = () => {
        source?.close()
        source = null
        if (!active) return
        reconnectTimer = setTimeout(connect, reconnectDelay(attempt))
        attempt += 1
      }
    }
    connect()
    return () => {
      active = false
      source?.close()
      if (reconnectTimer !== null) clearTimeout(reconnectTimer)
    }
  }, [eventUrl, refetchBackground, refetchSnapshot])
}

export function useBoardRealtime(
  boardId: string,
  refetchSnapshot: () => Promise<unknown>,
  refetchBackground?: () => Promise<unknown>,
): void {
  useRealtime(`/api/v1/admin/boards/${boardId}/events`, refetchSnapshot, refetchBackground)
}

export function usePublicBoardRealtime(
  shareToken: string,
  refetchSnapshot: () => Promise<unknown>,
  enabled: boolean,
  onDisplayReplaced: () => void,
  onDraft: (event: PublicDraftEvent) => "applied" | "ignored" | "recover",
): void {
  const eventUrl = `/api/v1/public/links/${encodeURIComponent(shareToken)}/display/events`
  useEffect(() => {
    if (!enabled || typeof EventSource === "undefined") return
    let active = true
    let attempt = 0
    let source: EventSource | null = null
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null
    const refetch = () => { void refetchSnapshot() }
    const handleDraft = (raw: Event) => {
      if (!(raw instanceof MessageEvent) || typeof raw.data !== "string") {
        refetch()
        return
      }
      try {
        if (onDraft(parsePublicDraftEvent(raw.data)) === "recover") refetch()
      } catch (error) {
        if (error instanceof SyntaxError || error instanceof z.ZodError) {
          refetch()
          return
        }
        throw error
      }
    }
    const connect = () => {
      if (!active) return
      source = new EventSource(eventUrl)
      source.onopen = () => { attempt = 0; refetch() }
      source.addEventListener("signature-draft", handleDraft)
      for (const type of EVENT_TYPES) {
        if (type !== "signature-draft" && type !== "signature-draft-cleared") source.addEventListener(type, refetch)
      }
      source.addEventListener("display-replaced", onDisplayReplaced)
      source.onerror = () => {
        source?.close()
        source = null
        if (!active) return
        reconnectTimer = setTimeout(connect, reconnectDelay(attempt))
        attempt += 1
      }
    }
    connect()
    return () => {
      active = false
      source?.close()
      if (reconnectTimer !== null) clearTimeout(reconnectTimer)
    }
  }, [enabled, eventUrl, onDisplayReplaced, onDraft, refetchSnapshot])
}
