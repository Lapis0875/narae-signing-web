import type { RosterIdentity } from "./rosterApi.ts"

type RosterAddFormProps = {
  readonly disabled: boolean
  readonly onAdd: (identity: RosterIdentity) => void
}

export function RosterAddForm({ disabled, onAdd }: RosterAddFormProps) {
  return (
    <form
      className="board-form board-section-spaced"
      onSubmit={(event) => {
        event.preventDefault()
        const form = event.currentTarget
        const values = new FormData(form)
        onAdd({
          job: values.get("job")?.toString() ?? "",
          name: values.get("name")?.toString() ?? "",
          organization: values.get("organization")?.toString() ?? "",
        })
        form.reset()
      }}
    >
      <h2 className="board-section-title">명단 직접 추가</h2>
      <label htmlFor="new-organization">소속</label>
      <input id="new-organization" name="organization" />
      <label htmlFor="new-job">직책</label>
      <input id="new-job" name="job" />
      <label htmlFor="new-name">이름</label>
      <input id="new-name" name="name" required />
      <button className="board-button board-button--primary" disabled={disabled} type="submit">명단 추가</button>
    </form>
  )
}
