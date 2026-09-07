import { useQuery } from "@tanstack/react-query"
import { useCallback, useEffect, useRef, useState } from "react"
import { useParams } from "react-router-dom"
import { FullViewCanvas } from "../features/boards/fullview/FullViewCanvas.tsx"
import {
  claimPublicDisplay,
  DISPLAY_ALREADY_CONNECTED_MESSAGE,
  fetchPublicDisplayBackground,
  fetchPublicDisplaySnapshot,
  fetchPublicDisplayTitle,
  heartbeatPublicDisplay,
  publicDisplayReleaseUrl,
  PublicDisplayDeniedError,
} from "../features/boards/fullview/publicDisplayApi.ts"
import type { FullViewSnapshot } from "../features/boards/fullview/fullViewApi.ts"
import {
  type PublicDraftEvent,
  mergePublicSnapshot,
  reconcilePublicDraft,
  usePublicBoardRealtime,
} from "../features/boards/fullview/realtime.ts"
import { useObjectUrl } from "../features/boards/editor/useObjectUrl.ts"

const DISPLAY_REPLACED_MESSAGE = "이 화면의 표시 연결이 다른 화면으로 전환되었습니다."

export function PublicDisplayRoute() {
  const { shareToken = "missing" } = useParams()
  const [terminalMessage, setTerminalMessage] = useState<string | null>(null)
  const [displaySnapshot, setDisplaySnapshot] = useState<FullViewSnapshot>()
  const displaySnapshotRef = useRef<FullViewSnapshot | undefined>(undefined)
  const claim = useQuery({
    queryFn: () => claimPublicDisplay(shareToken),
    queryKey: ["public", "boards", shareToken, "display-claim"],
  })
  const connected = claim.isSuccess && terminalMessage === null
  const title = useQuery({
    enabled: connected,
    queryFn: () => fetchPublicDisplayTitle(shareToken),
    queryKey: ["public", "boards", shareToken, "display-title"],
  })
  const snapshot = useQuery({
    enabled: connected,
    queryFn: () => fetchPublicDisplaySnapshot(shareToken),
    queryKey: ["public", "boards", shareToken, "snapshot"],
    refetchInterval: 5_000,
    refetchIntervalInBackground: true,
  })
  const background = useQuery({
    enabled: connected,
    queryFn: () => fetchPublicDisplayBackground(shareToken),
    queryKey: ["public", "boards", shareToken, "background"],
    refetchInterval: 5_000,
    refetchIntervalInBackground: true,
  })
  const backgroundUrl = useObjectUrl(background.data)
  const refetchSnapshot = useCallback(async () => {
    await Promise.all([snapshot.refetch(), background.refetch()])
  }, [background.refetch, snapshot.refetch])
  const showReplacement = useCallback(() => setTerminalMessage(DISPLAY_REPLACED_MESSAGE), [])
  const applyDraft = useCallback((event: PublicDraftEvent) => {
    const current = displaySnapshotRef.current
    if (current === undefined) return "recover" as const
    const result = reconcilePublicDraft(current, event)
    if (result.kind === "applied") {
      displaySnapshotRef.current = result.snapshot
      setDisplaySnapshot(result.snapshot)
    }
    return result.kind
  }, [])
  usePublicBoardRealtime(shareToken, refetchSnapshot, connected, showReplacement, applyDraft)

  useEffect(() => {
    if (snapshot.data === undefined) return
    const merged = displaySnapshotRef.current === undefined
      ? snapshot.data
      : mergePublicSnapshot(displaySnapshotRef.current, snapshot.data)
    displaySnapshotRef.current = merged
    setDisplaySnapshot(merged)
  }, [snapshot.data])

  useEffect(() => {
    if (!connected) return
    const heartbeat = setInterval(() => {
      void heartbeatPublicDisplay(shareToken).then((status) => {
        if (status === "denied") setTerminalMessage(DISPLAY_ALREADY_CONNECTED_MESSAGE)
      })
    }, 10_000)
    const release = () => { navigator.sendBeacon(publicDisplayReleaseUrl(shareToken)) }
    window.addEventListener("pagehide", release)
    return () => {
      clearInterval(heartbeat)
      window.removeEventListener("pagehide", release)
    }
  }, [connected, shareToken])

  if (terminalMessage !== null) return <main className="full-view-page full-view-page--public"><div className="full-view-canvas full-view-state" role="alert"><p>{terminalMessage}</p></div></main>
  if (claim.isPending) return <main className="full-view-page full-view-page--public"><div aria-busy="true" className="full-view-canvas full-view-state" role="status"><p>행사장 화면을 불러오는 중입니다.</p></div></main>
  if (claim.error !== null) {
    const message = claim.error instanceof PublicDisplayDeniedError ? DISPLAY_ALREADY_CONNECTED_MESSAGE : "행사장 화면을 불러오지 못했습니다."
    return <main className="full-view-page full-view-page--public"><div className="full-view-canvas full-view-state" role="alert"><p>{message}</p></div></main>
  }
  const initialError = title.error !== null || (snapshot.error !== null && snapshot.data === undefined) || (background.error !== null && background.data === undefined)
  if (initialError) return <main className="full-view-page full-view-page--public"><div className="full-view-canvas full-view-state" role="alert"><p>행사장 화면을 불러오지 못했습니다.</p></div></main>
  const canvas = displaySnapshot === undefined || background.data === undefined
    ? <div aria-busy="true" className="full-view-canvas full-view-state" role="status"><p>행사장 화면을 불러오는 중입니다.</p></div>
    : <FullViewCanvas backgroundUrl={backgroundUrl} snapshot={displaySnapshot} />
  return <main className="full-view-page full-view-page--public">{title.data === undefined ? null : <h1>{title.data}</h1>}{canvas}</main>
}
