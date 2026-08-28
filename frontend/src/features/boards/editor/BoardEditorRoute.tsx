import { useQuery, useQueryClient } from "@tanstack/react-query"
import { useCallback, useEffect, useRef, useState, type DragEvent, type ReactNode } from "react"
import { Link, useParams } from "react-router-dom"
import { ApiError, describeError } from "../../../api/errors.ts"
import { AppShell } from "../../../components/AppShell.tsx"
import { ForbiddenView, LoadingView } from "../../../components/AsyncViews.tsx"
import { useToast } from "../../../components/Toast.tsx"
import { BackgroundPanel } from "../background/BackgroundPanel.tsx"
import { fetchRoster, rosterQueryKey, type RosterEntry } from "../roster/rosterApi.ts"
import { SharePanel } from "../share/SharePanel.tsx"
import { BoardToolbar } from "./BoardToolbar.tsx"
import { CanvasViewport } from "./CanvasViewport.tsx"
import {
  fetchBoardDetail,
  fetchCurrentBackground,
  fetchShare,
  reissueShare,
  renameBoard,
  saveSlot,
  transitionBoard,
  unplaceSlot,
  uploadBackground,
  type SlotBackground,
} from "./editorApi.ts"
import { defaultPlacement, type Bounds } from "./geometry.ts"
import { SerializedSaveQueue, type SaveState } from "./saveQueue.ts"
import { useObjectUrl } from "./useObjectUrl.ts"
import { RosterManagementPanel } from "./RosterManagementPanel.tsx"
import { RealtimeBoardBridge } from "./RealtimeBoardBridge.tsx"
import "../list/boardControls.css"
import "./editor.css"

type RosterFilter = "all" | "pending" | "submitted" | "unplaced"
type BoardEditorRouteProps = { readonly actionExtensions?: ReactNode }

export function BoardEditorRoute({ actionExtensions }: BoardEditorRouteProps) {
  const { boardId = "missing" } = useParams()
  const queryClient = useQueryClient()
  const { showToast } = useToast()
  const queue = useRef(new SerializedSaveQueue())
  const [saveState, setSaveState] = useState<SaveState>("idle")
  const [filter, setFilter] = useState<RosterFilter>("all")
  const [busy, setBusy] = useState(false)
  const board = useQuery({ queryFn: () => fetchBoardDetail(boardId), queryKey: ["admin", "boards", boardId] })
  const background = useQuery({ queryFn: () => fetchCurrentBackground(boardId), queryKey: ["admin", "boards", boardId, "background"] })
  const roster = useQuery({ queryFn: () => fetchRoster(boardId), queryKey: rosterQueryKey(boardId) })
  const share = useQuery({ queryFn: () => fetchShare(boardId), queryKey: ["admin", "boards", boardId, "share"] })
  const backgroundUrl = useObjectUrl(background.data)
  const initialLoadError = board.error ?? roster.error

  useEffect(() => {
    if (initialLoadError !== null) showToast(`편집 화면을 불러오지 못했습니다. ${describeError(initialLoadError)}`)
  }, [initialLoadError, showToast])

  const refreshSnapshot = useCallback(async () => {
    await Promise.all([background.refetch(), board.refetch(), roster.refetch()])
  }, [background.refetch, board.refetch, roster.refetch])
  const reportFailure = async (error: unknown) => {
    setSaveState("failed")
    await refreshSnapshot()
    if (error instanceof ApiError) {
      showToast(error.status === 409 ? "서버 배치와 충돌했습니다. 최신 상태를 불러왔습니다." : error.message)
      return
    }
    showToast(describeError(error))
  }
  const queueSave = (entry: RosterEntry, bounds: Bounds, background: SlotBackground) => {
    setSaveState("saving")
    void queue.current.enqueueDebounced(entry.slot.id, async () => {
      await saveSlot(boardId, entry.slot.id, bounds, background)
      await roster.refetch()
      setSaveState("saved")
    }, reportFailure)
  }
  const runBoardMutation = async (mutation: () => Promise<void>) => {
    setBusy(true)
    try {
      await mutation()
      await refreshSnapshot()
    } catch (error) {
      if (error instanceof ApiError) showToast(error.message)
      else showToast(describeError(error))
    } finally {
      setBusy(false)
    }
  }
  const replaceBackground = async (file: File, adoptSourceRatio: boolean) => {
    setBusy(true)
    try {
      await uploadBackground(boardId, file, adoptSourceRatio, adoptSourceRatio)
      await refreshSnapshot()
      showToast("배경을 교체했습니다.")
    } catch (error) {
      if (error instanceof ApiError) showToast(error.message)
      else showToast(describeError(error))
    } finally {
      setBusy(false)
    }
  }
  const filteredEntries = (roster.data ?? []).filter((entry) => {
    switch (filter) {
      case "all": return true
      case "pending": return !entry.submitted && entry.slot.placementStatus === "PLACED"
      case "submitted": return entry.submitted
      case "unplaced": return entry.slot.placementStatus === "UNPLACED"
    }
    return false
  })

  if (board.isPending || roster.isPending || share.isPending) return <LoadingView />
  if (initialLoadError instanceof ApiError && initialLoadError.status === 403) return <ForbiddenView />
  if (board.data === undefined || roster.data === undefined) {
    return <AppShell><section className="app-panel"><p role="alert">편집 화면을 불러오지 못했습니다.</p></section></AppShell>
  }

  return (
    <AppShell>
      <RealtimeBoardBridge boardId={boardId} refetchSnapshot={refreshSnapshot} />
      <main className="board-editor board-workflow">
        <h1 className="editor-route-title">{board.data.title}</h1>
        <BoardToolbar
          actionExtensions={board.data.status === "서명 진행" ? null : actionExtensions}
          board={board.data}
          disabled={busy}
          onRename={(title) => runBoardMutation(async () => { await renameBoard(boardId, title) })}
          onTransition={(action) => runBoardMutation(async () => { await transitionBoard(boardId, action); showToast("서명 상태를 변경했습니다.") })}
          saveState={saveState}
        />
        <nav className="editor-links" aria-label="보드 편집 이동"><Link className="board-button" to="/boards">보드 목록</Link><Link className="board-button board-button--primary" rel="noopener noreferrer" target="_blank" to={`/boards/${boardId}/full`}>전체보기</Link></nav>
        <div className="editor-layout">
          <div className="editor-main-column">
            <section className="editor-canvas-panel">
              {background.isPending || background.isError ? (
                <div aria-busy={background.isPending} className="editor-canvas editor-background-state" role={background.isError ? "alert" : "status"} style={{ aspectRatio: `${board.data.canvasWidth} / ${board.data.canvasHeight}` }}>
                  <p>{background.isError ? "배경을 불러오지 못했습니다. 다시 시도해 주세요." : "배경을 불러오는 중입니다."}</p>
                  {background.isError ? <button className="board-button board-button--primary" disabled={background.isFetching} onClick={() => void background.refetch()} type="button">배경 다시 불러오기</button> : null}
                </div>
              ) : (
                <CanvasViewport
                  backgroundUrl={backgroundUrl}
                  canvasHeight={board.data.canvasHeight}
                  canvasWidth={board.data.canvasWidth}
                  entries={roster.data}
                  onSave={queueSave}
                  onUnplace={(entry) => {
                    setSaveState("saving")
                    void queue.current.enqueue(async () => { await unplaceSlot(boardId, entry.slot.id); await roster.refetch(); setSaveState("saved") }, reportFailure)
                  }}
                />
              )}
            </section>
            <section className="editor-panel editor-operations" aria-labelledby="operations-title">
              <h2 id="operations-title">운영 현황</h2>
              <fieldset className="editor-actions editor-filter" aria-label="명단 상태 필터">
                {(["all", "unplaced", "pending", "submitted"] as const).map((value) => <button aria-pressed={filter === value} className="board-button" key={value} onClick={() => setFilter(value)} type="button">{{ all: "전체", pending: "미제출", submitted: "제출", unplaced: "미배치" }[value]}</button>)}
              </fieldset>
              <ul className="editor-roster-list">{filteredEntries.map((entry) => <li key={entry.id}>{entry.identity.name} · {entry.slot.placementStatus === "UNPLACED" ? "미배치" : entry.submitted ? "제출" : "미제출"}</li>)}</ul>
            </section>
            <RosterManagementPanel boardId={boardId} entries={roster.data} />
          </div>
          <aside className="editor-sidebar">
            <section className="editor-panel" aria-labelledby="unplaced-title">
              <h2 id="unplaced-title">미배치 명단</h2>
              <ul className="editor-roster-list">
                {roster.data.filter((entry) => entry.slot.placementStatus === "UNPLACED").map((entry) => (
                  <li key={entry.id}><button aria-label={`${entry.identity.name} 배치`} className="board-button" draggable onClick={() => queueSave(entry, defaultPlacement(roster.data.filter((candidate) => candidate.slot.placementStatus === "PLACED").length), "transparent")} onDragStart={(event: DragEvent<HTMLButtonElement>) => event.dataTransfer.setData("text/slot-id", entry.slot.id)} type="button">{entry.identity.name}</button></li>
                ))}
              </ul>
            </section>
            <BackgroundPanel disabled={busy || background.isError} onUpload={replaceBackground} />
            {share.data === undefined ? <section className="editor-panel"><h2>공유</h2><p>공유 링크를 불러오지 못했습니다.</p></section> : <SharePanel disabled={busy} onReissue={async () => { await runBoardMutation(async () => { await reissueShare(boardId); await queryClient.invalidateQueries({ queryKey: ["admin", "boards", boardId, "share"] }); showToast("공유 링크를 재발급했습니다.") }) }} share={share.data} />}
          </aside>
        </div>
      </main>
    </AppShell>
  )
}
