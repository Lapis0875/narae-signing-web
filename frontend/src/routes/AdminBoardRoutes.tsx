import { Route } from "react-router-dom"
import { BoardCreateRoute } from "../features/boards/list/BoardCreateRoute.tsx"
import { BoardListRoute } from "../features/boards/list/BoardListRoute.tsx"
import { RosterEditorRoute } from "../features/boards/roster/RosterEditorRoute.tsx"

export const adminBoardRoutes = (
  <>
    <Route path="/boards" element={<BoardListRoute />} />
    <Route path="/boards/new" element={<BoardCreateRoute />} />
    <Route path="/boards/:boardId/edit" element={<RosterEditorRoute />} />
  </>
)
