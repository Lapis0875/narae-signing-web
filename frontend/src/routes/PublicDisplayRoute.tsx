import { useQuery } from "@tanstack/react-query"
import { useCallback } from "react"
import { useParams } from "react-router-dom"
import { FullViewCanvas } from "../features/boards/fullview/FullViewCanvas.tsx"
import { fetchPublicDisplayBackground, fetchPublicDisplaySnapshot } from "../features/boards/fullview/publicDisplayApi.ts"
import { usePublicBoardRealtime } from "../features/boards/fullview/realtime.ts"
import { useObjectUrl } from "../features/boards/editor/useObjectUrl.ts"
import { readPublicLink } from "../features/signing/flow/publicSignerApi.ts"

export function PublicDisplayRoute() {
  const { shareToken = "missing" } = useParams()
  const link = useQuery({
    queryFn: () => readPublicLink(shareToken),
    queryKey: ["public", "boards", shareToken, "link"],
  })
  const visible = link.data?.state === "OPEN" || link.data?.state === "CLOSED"
  const snapshot = useQuery({
    enabled: visible,
    queryFn: () => fetchPublicDisplaySnapshot(shareToken),
    queryKey: ["public", "boards", shareToken, "snapshot"],
    refetchInterval: 5_000,
    refetchIntervalInBackground: true,
  })
  const background = useQuery({
    enabled: visible,
    queryFn: () => fetchPublicDisplayBackground(shareToken),
    queryKey: ["public", "boards", shareToken, "background"],
    refetchInterval: 5_000,
    refetchIntervalInBackground: true,
  })
  const backgroundUrl = useObjectUrl(background.data)
  const refetchSnapshot = useCallback(async () => {
    await Promise.all([snapshot.refetch(), background.refetch()])
  }, [background.refetch, snapshot.refetch])
  usePublicBoardRealtime(shareToken, refetchSnapshot, visible)

  if (link.isPending) return <main className="full-view-page"><div aria-busy="true" className="full-view-canvas full-view-state" role="status"><p>행사장 화면을 불러오는 중입니다.</p></div></main>
  if (link.error !== null || link.data === undefined || link.data.state === "INVALID") {
    return <main className="full-view-page"><div className="full-view-canvas full-view-state" role="alert"><p>행사장 화면을 불러오지 못했습니다.</p></div></main>
  }
  if (!visible) {
    return <main className="full-view-page"><h1>{link.data.title}</h1><div className="full-view-canvas full-view-state" role="status"><p>아직 서명 준비 중입니다.</p></div></main>
  }
  const canvas = snapshot.data === undefined || background.isPending || snapshot.error !== null || background.error !== null
    ? <div aria-busy={snapshot.isPending || background.isPending} className="full-view-canvas full-view-state" role={snapshot.error !== null || background.error !== null ? "alert" : "status"}><p>{snapshot.error !== null || background.error !== null ? "행사장 화면을 불러오지 못했습니다." : "행사장 화면을 불러오는 중입니다."}</p></div>
    : <FullViewCanvas backgroundUrl={backgroundUrl} snapshot={snapshot.data} />
  return <main className="full-view-page"><h1>{link.data.title}</h1>{canvas}</main>
}
