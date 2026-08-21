import { useState } from "react"
import { ConfirmDialog } from "../../../components/ConfirmDialog.tsx"
import type { RosterEntry, RosterIdentity } from "./rosterApi.ts"

type RosterTableProps = {
  readonly entries: readonly RosterEntry[]
  readonly isSaving: boolean
  readonly onDelete: (entryId: string) => void
  readonly onSave: (entryId: string, identity: RosterIdentity) => void
}

export function RosterTable({ entries, isSaving, onDelete, onSave }: RosterTableProps) {
  const [deleteEntryId, setDeleteEntryId] = useState<string | null>(null)

  return (
    <>
      {entries.length === 0 ? <p>등록된 명단이 없습니다.</p> : (
        <ul className="roster-list">
          {entries.map((entry, index) => (
            <li className="board-card" key={entry.id}>
              <form
                className="board-form"
                onSubmit={(event) => {
                  event.preventDefault()
                  const values = new FormData(event.currentTarget)
                  onSave(entry.id, {
                    job: values.get("job")?.toString() ?? "",
                    name: values.get("name")?.toString() ?? "",
                    organization: values.get("organization")?.toString() ?? "",
                  })
                }}
              >
                <h3 className="board-card-title">명단 {index + 1}</h3>
                <label htmlFor={`organization-${entry.id}`}>소속</label>
                <input defaultValue={entry.identity.organization} id={`organization-${entry.id}`} name="organization" />
                <label htmlFor={`job-${entry.id}`}>직책</label>
                <input defaultValue={entry.identity.job} id={`job-${entry.id}`} name="job" />
                <label htmlFor={`name-${entry.id}`}>이름</label>
                <input defaultValue={entry.identity.name} id={`name-${entry.id}`} name="name" required />
                <div className="board-actions">
                  <button className="board-button" disabled={isSaving || entry.submitted} type="submit">수정 저장</button>
                  <button
                    className="board-button board-button--destructive"
                    disabled={isSaving || entry.submitted}
                    onClick={() => setDeleteEntryId(entry.id)}
                    type="button"
                  >삭제</button>
                </div>
                {entry.submitted ? <p>서명이 제출되어 명단을 바꿀 수 없습니다.</p> : null}
              </form>
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog
        confirmLabel="삭제"
        message="선택한 명단을 삭제할까요?"
        onCancel={() => setDeleteEntryId(null)}
        onConfirm={() => {
          if (deleteEntryId !== null) {
            onDelete(deleteEntryId)
          }
          setDeleteEntryId(null)
        }}
        open={deleteEntryId !== null}
      />
    </>
  )
}
