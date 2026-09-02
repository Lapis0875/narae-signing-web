import { useEffect } from "react"

const EVENT_TYPES = [
  "background-updated",
  "board-deleted",
  "board-state-updated",
  "board-updated",
  "layout-updated",
  "signature-reset",
  "signature-submitted",
  "signature-draft",
  "signature-draft-cleared",
] as const

export function reconnectDelay(attempt: number): number {
  return Math.min(4_000, 250 * 2 ** attempt)
}

function useRealtime(
  eventUrl: string,
  refetchSnapshot: () => Promise<unknown>,
  enabled = true,
): void {
  useEffect(() => {
    if (!enabled || typeof EventSource === "undefined") return
    let active = true
    let attempt = 0
    let source: EventSource | null = null
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null
    let refresh: Promise<unknown> | null = null
    let refreshQueued = false
    let followup = false
    const runRefetch = () => {
      refreshQueued = false
      if (!active) return
      if (refresh !== null) {
        followup = true
        return
      }
      refresh = refetchSnapshot().finally(() => {
        refresh = null
        if (followup) {
          followup = false
          refetch()
        }
      })
    }
    const refetch = () => {
      if (refresh !== null) {
        followup = true
        return
      }
      if (refreshQueued) return
      refreshQueued = true
      queueMicrotask(runRefetch)
    }
    const connect = () => {
      if (!active) return
      source = new EventSource(eventUrl)
      source.onopen = () => { attempt = 0; refetch() }
      for (const type of EVENT_TYPES) source.addEventListener(type, refetch)
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
  }, [enabled, eventUrl, refetchSnapshot])
}

export function useBoardRealtime(boardId: string, refetchSnapshot: () => Promise<unknown>): void {
  useRealtime(`/api/v1/admin/boards/${boardId}/events`, refetchSnapshot)
}

export function usePublicBoardRealtime(
  shareToken: string,
  refetchSnapshot: () => Promise<unknown>,
  enabled: boolean,
): void {
  useRealtime(
    `/api/v1/public/links/${encodeURIComponent(shareToken)}/display/events`,
    refetchSnapshot,
    enabled,
  )
}
