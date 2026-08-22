import { Route } from "react-router-dom"
import { BoardCreateRoute } from "../features/boards/list/BoardCreateRoute.tsx"
import { BoardListRoute } from "../features/boards/list/BoardListRoute.tsx"
import { BoardEditorRoute } from "../features/boards/editor/BoardEditorRoute.tsx"
import { FinalPngAction } from "../features/boards/editor/FinalPngAction.tsx"
import { DeleteBoardAction } from "../features/boards/editor/DeleteBoardAction.tsx"

export const adminBoardRoutes = (
  <>
    <Route path="/boards" element={<BoardListRoute />} />
    <Route path="/boards/new" element={<BoardCreateRoute />} />
    <Route
      path="/boards/:boardId/edit"
      element={<BoardEditorRoute actionExtensions={<><FinalPngAction /><DeleteBoardAction /></>} />}
    />
  </>
)
