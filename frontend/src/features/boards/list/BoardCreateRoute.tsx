import { useMutation, useQueryClient } from "@tanstack/react-query"
import { useState } from "react"
import { Link, useNavigate } from "react-router-dom"
import { AppShell } from "../../../components/AppShell.tsx"
import { useToast } from "../../../components/Toast.tsx"
import { boardListQueryKey, createBoard } from "./boardApi.ts"
import "./boardControls.css"

export function BoardCreateRoute() {
  const [title, setTitle] = useState("")
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { showToast } = useToast()
  const creation = useMutation({
    mutationFn: createBoard,
    onSuccess: async (board) => {
      await queryClient.invalidateQueries({ queryKey: boardListQueryKey })
      showToast("보드를 만들었습니다.")
      navigate(`/boards/${board.id}/edit`)
    },
  })

  return (
    <AppShell>
      <section className="app-panel route-panel board-workflow">
        <h1>새 보드</h1>
        <form
          className="board-form"
          onSubmit={(event) => {
            event.preventDefault()
            const normalizedTitle = title.trim()
            if (normalizedTitle.length > 0) {
              creation.mutate(normalizedTitle)
            }
          }}
        >
          <label htmlFor="board-title">보드 제목</label>
          <input
            autoComplete="off"
            id="board-title"
            maxLength={120}
            onChange={(event) => setTitle(event.target.value)}
            required
            value={title}
          />
          {creation.error !== null ? <p role="alert">보드를 만들지 못했습니다. 다시 시도해 주세요.</p> : null}
          <button className="app-primary-button board-button board-button--primary" disabled={creation.isPending} type="submit">
            {creation.isPending ? "만드는 중" : "보드 만들기"}
          </button>
        </form>
        <Link to="/boards">목록으로 돌아가기</Link>
      </section>
    </AppShell>
  )
}
