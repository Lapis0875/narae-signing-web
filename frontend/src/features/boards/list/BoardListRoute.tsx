import { useQuery } from "@tanstack/react-query"
import { Link } from "react-router-dom"
import { AppShell } from "../../../components/AppShell.tsx"
import { ForbiddenView, LoadingView } from "../../../components/AsyncViews.tsx"
import { ApiError } from "../../../api/errors.ts"
import { boardListQueryKey, fetchBoards } from "./boardApi.ts"
import "./boardControls.css"

export function BoardListRoute() {
  const boards = useQuery({ queryFn: fetchBoards, queryKey: boardListQueryKey })

  if (boards.isPending) {
    return <LoadingView />
  }
  if (boards.error instanceof ApiError && boards.error.status === 403) {
    return <ForbiddenView />
  }

  return (
    <AppShell>
      <section className="app-panel route-panel board-workflow">
        <h1>보드 목록</h1>
        <div className="board-actions board-section-spaced">
          <Link className="app-primary-button board-button board-button--primary" to="/boards/new">
            새 보드 만들기
          </Link>
          <button className="board-button" onClick={() => void boards.refetch()} type="button">목록 새로고침</button>
        </div>
        {boards.error !== null ? (
          <div role="alert">
            <p>보드 목록을 불러오지 못했습니다.</p>
            <button className="board-button" onClick={() => void boards.refetch()} type="button">다시 불러오기</button>
          </div>
        ) : boards.data.length === 0 ? (
          <p>아직 만든 보드가 없습니다.</p>
        ) : (
          <ul className="board-list">
            {boards.data.map((board) => (
              <li className="board-card" key={board.id}>
                <h2 className="board-card-title">{board.title}</h2>
                <p>{board.status}</p>
                <div className="board-links">
                  <Link to={`/boards/${board.id}/edit`}>명단 편집</Link>
                  <Link to={`/boards/${board.id}/full`}>전체보기</Link>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </AppShell>
  )
}
