import type { ReactNode } from "react"
import type { Board } from "../list/boardApi.ts"
import type { SaveState } from "./saveQueue.ts"

type BoardToolbarProps = {
  readonly actionExtensions?: ReactNode
  readonly board: Board
  readonly disabled: boolean
  readonly onRename: (title: string) => Promise<void>
  readonly onTransition: (action: "open" | "close" | "reopen") => Promise<void>
  readonly saveState: SaveState
}

const saveLabels: Record<SaveState, string> = {
  failed: "저장 실패",
  idle: "변경 없음",
  saved: "저장됨",
  saving: "저장 중",
}

export function BoardToolbar({
  actionExtensions,
  board,
  disabled,
  onRename,
  onTransition,
  saveState,
}: BoardToolbarProps) {
  return (
    <header className="editor-toolbar">
      <form
        className="editor-title-form"
        onSubmit={(event) => {
          event.preventDefault()
          const title = new FormData(event.currentTarget).get("title")
          if (typeof title === "string" && title.trim().length > 0) void onRename(title)
        }}
      >
        <input aria-label="보드 제목" defaultValue={board.title} disabled={disabled} name="title" />
        <button className="board-button" disabled={disabled} type="submit">제목 저장</button>
      </form>
      <span className="editor-status">{board.status}</span>
      <output aria-label="자동 저장 상태">{saveLabels[saveState]}</output>
      <div className="editor-actions">
        {board.status === "설정 중" ? (
          <button className="board-button board-button--primary" disabled={disabled} onClick={() => void onTransition("open")} type="button">서명 시작</button>
        ) : null}
        {board.status === "서명 진행" ? (
          <button className="board-button board-button--destructive" disabled={disabled} onClick={() => void onTransition("close")} type="button">마감</button>
        ) : null}
        {board.status === "마감/보관" ? (
          <button className="board-button board-button--primary" disabled={disabled} onClick={() => void onTransition("reopen")} type="button">다시 열기</button>
        ) : null}
        {actionExtensions}
      </div>
    </header>
  )
}
