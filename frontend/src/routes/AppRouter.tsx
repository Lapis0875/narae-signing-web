import { Navigate, Route, Routes, useParams } from "react-router-dom"
import { UnsupportedDeviceView } from "../components/UnsupportedDeviceView.tsx"
import { RouteShell } from "./RouteShell.tsx"
import { useTabletSupport } from "./useTabletSupport.ts"

function BoardRoute({ mode }: { readonly mode: "edit" | "full" }) {
  const { boardId = "missing" } = useParams()
  const title = mode === "edit" ? "보드 편집" : "보드 전체보기"
  return (
    <RouteShell admin endpoint={`/api/v1/admin/boards/${boardId}`} title={title}>
      {mode === "full" ? <div data-testid="full-view-canvas" /> : null}
    </RouteShell>
  )
}

function PublicSignRoute() {
  const { shareToken = "missing" } = useParams()
  const isSupported = useTabletSupport()

  if (!isSupported) {
    return <UnsupportedDeviceView />
  }

  return (
    <RouteShell endpoint={`/api/v1/public/sign/${shareToken}`} title="서명하기">
      <canvas aria-label="서명 입력 영역" data-testid="signer-canvas" />
    </RouteShell>
  )
}

// TODO11_AUTH_ROUTES_START
import { AdminRouteGuard } from "./AdminRouteGuard.tsx"
import { LoginRoute } from "./LoginRoute.tsx"

const authRoutes = (
  <>
    <Route path="/login" element={<LoginRoute />} />
    <Route element={<AdminRouteGuard />}>
      <Route path="/boards" element={<RouteShell admin endpoint="/api/v1/admin/boards" title="보드 목록" />} />
      <Route path="/boards/new" element={<RouteShell admin endpoint="/api/v1/admin/boards/new" title="새 보드" />} />
      <Route path="/boards/:boardId/edit" element={<BoardRoute mode="edit" />} />
      <Route path="/boards/:boardId/full" element={<BoardRoute mode="full" />} />
    </Route>
  </>
)
// TODO11_AUTH_ROUTES_END

// TODO17_ADMIN_BOARD_ROUTES_START
const adminBoardRoutes = (
  <>
    <Route path="/boards" element={<RouteShell admin endpoint="/api/v1/admin/boards" title="보드 목록" />} />
    <Route path="/boards/new" element={<RouteShell admin endpoint="/api/v1/admin/boards/new" title="새 보드" />} />
    <Route path="/boards/:boardId/edit" element={<BoardRoute mode="edit" />} />
  </>
)
// TODO17_ADMIN_BOARD_ROUTES_END

// TODO23_FULL_VIEW_ROUTE_START
const fullViewRoute = <Route path="/boards/:boardId/full" element={<BoardRoute mode="full" />} />
// TODO23_FULL_VIEW_ROUTE_END

// TODO24_PUBLIC_SIGN_ROUTE_START
const publicSignRoute = <Route path="/sign/:shareToken" element={<PublicSignRoute />} />
// TODO24_PUBLIC_SIGN_ROUTE_END

export function AppRouter() {
  return (
    <Routes>
      {authRoutes}
      {adminBoardRoutes}
      {fullViewRoute}
      {publicSignRoute}
      <Route path="*" element={<Navigate replace to="/login" />} />
    </Routes>
  )
}
