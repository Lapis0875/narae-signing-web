import { useParams } from "react-router-dom"
import { RouteShell } from "./RouteShell.tsx"

export function FullViewRoute() {
  const { boardId = "missing" } = useParams()
  return (
    <RouteShell admin endpoint={`/api/v1/admin/boards/${boardId}`} title="보드 전체보기">
      <div data-testid="full-view-canvas" />
    </RouteShell>
  )
}
