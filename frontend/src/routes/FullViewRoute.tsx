import { useQuery } from "@tanstack/react-query"
import { useCallback } from "react"
import { useParams } from "react-router-dom"
import { FullViewCanvas } from "../features/boards/fullview/FullViewCanvas.tsx"
import { fetchFullViewBackground, fetchFullViewSnapshot } from "../features/boards/fullview/fullViewApi.ts"
import { useBoardRealtime } from "../features/boards/fullview/realtime.ts"
import { useObjectUrl } from "../features/boards/editor/useObjectUrl.ts"

export function FullViewRoute() {
  const { boardId = "missing" } = useParams()
  const snapshot = useQuery({
    queryFn: () => fetchFullViewSnapshot(boardId),
    queryKey: ["admin", "boards", boardId, "snapshot"],
    refetchInterval: 5_000,
    refetchIntervalInBackground: true,
  })
  const background = useQuery({
    queryFn: () => fetchFullViewBackground(boardId),
    queryKey: ["admin", "boards", boardId, "background"],
    refetchInterval: 5_000,
    refetchIntervalInBackground: true,
  })
  const backgroundUrl = useObjectUrl(background.data)
  const refetchSnapshot = useCallback(async () => {
    await snapshot.refetch()
  }, [snapshot.refetch])
  const refetchBackground = useCallback(async () => {
    await background.refetch()
  }, [background.refetch])
  useBoardRealtime(boardId, refetchSnapshot, refetchBackground)

  const canvas = snapshot.data === undefined || background.isPending || snapshot.error !== null || background.error !== null
    ? <div aria-busy={snapshot.isPending || background.isPending} className="full-view-canvas full-view-state" data-testid="full-view-canvas" role={snapshot.error !== null || background.error !== null ? "alert" : "status"}><p>{snapshot.error !== null || background.error !== null ? "전체보기를 불러오지 못했습니다." : "전체보기를 불러오는 중입니다."}</p></div>
    : <FullViewCanvas backgroundUrl={backgroundUrl} snapshot={snapshot.data} />
  return (
    <main className="full-view-page">
      <h1>보드 전체보기</h1>
      {canvas}
    </main>
  )
}
