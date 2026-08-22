import { useMutation, useQueryClient } from "@tanstack/react-query"
import { ApiError } from "../../../api/errors.ts"
import { useToast } from "../../../components/Toast.tsx"
import { RosterAddForm } from "../roster/RosterAddForm.tsx"
import { RosterImportPanel } from "../roster/RosterImportPanel.tsx"
import { RosterTable } from "../roster/RosterTable.tsx"
import {
  createRosterEntry,
  deleteRosterEntry,
  describeRosterError,
  importRosterFile,
  replaceRoster,
  RosterImportRejectedError,
  rosterQueryKey,
  updateRosterEntry,
  type RosterEntry,
  type RosterIdentity,
} from "../roster/rosterApi.ts"
import type { RosterPreview } from "../roster/rosterPreview.ts"

type RosterManagementPanelProps = {
  readonly boardId: string
  readonly entries: readonly RosterEntry[]
}

export function RosterManagementPanel({ boardId, entries }: RosterManagementPanelProps) {
  const queryClient = useQueryClient()
  const { showToast } = useToast()
  const refresh = async () => queryClient.invalidateQueries({ queryKey: rosterQueryKey(boardId) })
  const notifyFailure = (action: string, error: unknown) => showToast(`${action}.\n${describeRosterError(error)}`)
  const addition = useMutation({
    mutationFn: (identity: RosterIdentity) => createRosterEntry(boardId, identity),
    onError: (error) => notifyFailure("명단을 추가하지 못했습니다", error),
    onSuccess: async () => { await refresh(); showToast("명단을 추가했습니다.") },
  })
  const update = useMutation({
    mutationFn: ({ entryId, identity }: { readonly entryId: string; readonly identity: RosterIdentity }) => updateRosterEntry(boardId, entryId, identity),
    onError: (error) => notifyFailure("명단을 수정하지 못했습니다", error),
    onSuccess: async () => { await refresh(); showToast("명단을 수정했습니다.") },
  })
  const removal = useMutation({
    mutationFn: (entryId: string) => deleteRosterEntry(boardId, entryId),
    onError: (error) => notifyFailure("명단을 삭제하지 못했습니다", error),
    onSuccess: async () => { await refresh(); showToast("명단을 삭제했습니다.") },
  })
  const replacement = useMutation({
    mutationFn: (rows: readonly RosterIdentity[]) => replaceRoster(boardId, rows),
    onError: (error) => notifyFailure("서버 명단으로 갱신하지 못했습니다", error),
    onSuccess: (snapshot) => { queryClient.setQueryData(rosterQueryKey(boardId), snapshot); showToast("서버 명단으로 갱신했습니다.") },
  })
  const fileImport = useMutation({
    mutationFn: ({ file }: { readonly file: File; readonly preview: RosterPreview }) => importRosterFile(boardId, file),
    onError: (error) => notifyFailure("파일 명단을 가져오지 못했습니다", error),
    onSuccess: (snapshot) => { queryClient.setQueryData(rosterQueryKey(boardId), snapshot); showToast("파일 명단을 가져왔습니다.") },
  })
  const isSaving = addition.isPending || update.isPending || removal.isPending || replacement.isPending || fileImport.isPending
  const mutationError = addition.error ?? update.error ?? removal.error ?? replacement.error ?? fileImport.error
  const serverMarkers = fileImport.error instanceof RosterImportRejectedError ? fileImport.error.markers : []

  return (
    <section className="editor-panel editor-roster-management" aria-labelledby="roster-management-title">
      <h2 id="roster-management-title">명단 편집</h2>
      {mutationError !== null ? <p aria-label="명단 유지 안내" role="alert">입력 내용을 반영하지 못했습니다. 표시된 서버 명단은 그대로 유지됩니다.</p> : null}
      {serverMarkers.length > 0 ? (
        <ol aria-label="서버 입력 오류" role="alert">
          {serverMarkers.map((marker) => <li data-row={marker.row} key={`${marker.row}-${marker.message}`}>{marker.row === 0 ? "파일" : `${marker.row}행`}: {marker.message}</li>)}
        </ol>
      ) : null}
      <RosterAddForm disabled={isSaving} onAdd={(identity) => addition.mutateAsync(identity)
        .then(() => true)
        .catch((error: unknown) => {
          if (error instanceof ApiError) return false
          throw error
        })} />
      <RosterTable entries={entries} isSaving={isSaving} onDelete={(entryId) => removal.mutate(entryId)} onSave={(entryId, identity) => update.mutate({ entryId, identity })} />
      <RosterImportPanel isSaving={isSaving} onImportFile={(file, preview) => fileImport.mutate({ file, preview })} onReplace={(rows) => replacement.mutate(rows)} />
    </section>
  )
}
