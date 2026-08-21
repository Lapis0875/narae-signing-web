import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Link, useParams } from "react-router-dom"
import { AppShell } from "../../../components/AppShell.tsx"
import { ForbiddenView, LoadingView } from "../../../components/AsyncViews.tsx"
import { useToast } from "../../../components/Toast.tsx"
import { ApiError } from "../../../api/errors.ts"
import { fetchBoard } from "../list/boardApi.ts"
import "../list/boardControls.css"
import { RosterImportPanel } from "./RosterImportPanel.tsx"
import { RosterAddForm } from "./RosterAddForm.tsx"
import { RosterTable } from "./RosterTable.tsx"
import type { RosterPreview } from "./rosterPreview.ts"
import {
  deleteRosterEntry,
  createRosterEntry,
  fetchRoster,
  importRosterFile,
  replaceRoster,
  RosterImportRejectedError,
  rosterQueryKey,
  updateRosterEntry,
  type RosterEntry,
  type RosterIdentity,
} from "./rosterApi.ts"

export function RosterEditorRoute() {
  const { boardId = "missing" } = useParams()
  const queryClient = useQueryClient()
  const { showToast } = useToast()
  const board = useQuery({ queryFn: () => fetchBoard(boardId), queryKey: ["admin", "boards", boardId] })
  const roster = useQuery({ queryFn: () => fetchRoster(boardId), queryKey: rosterQueryKey(boardId) })

  const acceptSnapshot = (entries: readonly RosterEntry[]) => {
    queryClient.setQueryData(rosterQueryKey(boardId), entries)
  }
  const update = useMutation({
    mutationFn: ({ entryId, identity }: { readonly entryId: string; readonly identity: RosterIdentity }) =>
      updateRosterEntry(boardId, entryId, identity),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: rosterQueryKey(boardId) })
      showToast("명단을 수정했습니다.")
    },
  })
  const addition = useMutation({
    mutationFn: (identity: RosterIdentity) => createRosterEntry(boardId, identity),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: rosterQueryKey(boardId) })
      showToast("명단을 추가했습니다.")
    },
  })
  const removal = useMutation({
    mutationFn: (entryId: string) => deleteRosterEntry(boardId, entryId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: rosterQueryKey(boardId) })
      showToast("명단을 삭제했습니다.")
    },
  })
  const replacement = useMutation({
    mutationFn: (rows: readonly RosterIdentity[]) => replaceRoster(boardId, rows),
    onSuccess: (entries) => {
      acceptSnapshot(entries)
      showToast("서버 명단으로 갱신했습니다.")
    },
  })
  const fileImport = useMutation({
    mutationFn: ({ file }: { readonly file: File; readonly preview: RosterPreview }) =>
      importRosterFile(boardId, file),
    onSuccess: (entries) => {
      acceptSnapshot(entries)
      showToast("파일 명단을 가져왔습니다.")
    },
  })
  const isSaving = addition.isPending || update.isPending || removal.isPending
    || replacement.isPending || fileImport.isPending
  const mutationError = addition.error ?? update.error ?? removal.error ?? replacement.error ?? fileImport.error
  const serverMarkers = fileImport.error instanceof RosterImportRejectedError ? fileImport.error.markers : []

  if (board.isPending || roster.isPending) {
    return <LoadingView />
  }
  const forbiddenError = board.error ?? roster.error
  if (forbiddenError instanceof ApiError && forbiddenError.status === 403) {
    return <ForbiddenView />
  }

  return (
    <AppShell>
      <section className="app-panel route-panel board-workflow">
        <h1>{board.data?.title ?? "보드 명단 편집"}</h1>
        <div className="board-links board-section-spaced">
          <Link to="/boards">보드 목록</Link>
          <Link to={`/boards/${boardId}/full`}>전체보기</Link>
          <button className="board-button" onClick={() => void roster.refetch()} type="button">명단 새로고침</button>
        </div>
        {board.error !== null || roster.error !== null ? (
          <div role="alert">
            <p>보드 명단을 불러오지 못했습니다.</p>
            <button className="board-button" onClick={() => void roster.refetch()} type="button">다시 불러오기</button>
          </div>
        ) : (
          <>
            {mutationError !== null ? (
              <p aria-label="명단 유지 안내" role="alert">
                입력 내용을 반영하지 못했습니다. 표시된 <span className="board-keep-together">서버 명단은</span> 그대로 유지됩니다.
              </p>
            ) : null}
            {serverMarkers.length > 0 ? (
              <ol aria-label="서버 입력 오류" role="alert">
                {serverMarkers.map((marker) => (
                  <li data-row={marker.row} key={`${marker.row}-${marker.message}`}>
                    {marker.row === 0 ? "파일" : `${marker.row}행`}: {marker.message}
                  </li>
                ))}
              </ol>
            ) : null}
            <RosterAddForm disabled={isSaving} onAdd={(identity) => addition.mutate(identity)} />
            <RosterTable
              entries={roster.data}
              isSaving={isSaving}
              onDelete={(entryId) => removal.mutate(entryId)}
              onSave={(entryId, identity) => update.mutate({ entryId, identity })}
            />
            <RosterImportPanel
              isSaving={isSaving}
              onImportFile={(file, preview) => fileImport.mutate({ file, preview })}
              onReplace={(rows) => replacement.mutate(rows)}
            />
          </>
        )}
      </section>
    </AppShell>
  )
}
