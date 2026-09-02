import { Navigate, Route, Routes } from "react-router-dom"
import { adminBoardRoutes } from "./AdminBoardRoutes.tsx"
import { FullViewRoute } from "./FullViewRoute.tsx"
import { PublicSignerRoute } from "./PublicSignerRoute.tsx"
import { PublicDisplayRoute } from "./PublicDisplayRoute.tsx"

// TODO11_AUTH_ROUTES_START
import { AdminRouteGuard } from "./AdminRouteGuard.tsx"
import { LoginRoute } from "./LoginRoute.tsx"

// TODO23_FULL_VIEW_ROUTE_START
const fullViewRoute = <Route path="/boards/:boardId/full" element={<FullViewRoute />} />
// TODO23_FULL_VIEW_ROUTE_END

const authRoutes = (
  <>
    <Route path="/login" element={<LoginRoute />} />
    <Route element={<AdminRouteGuard />}>
      {adminBoardRoutes}
      {fullViewRoute}
    </Route>
  </>
)
// TODO11_AUTH_ROUTES_END

// TODO24_PUBLIC_SIGN_ROUTE_START
const publicSignRoute = <Route path="/sign/:shareToken" element={<PublicSignerRoute />} />
const publicDisplayRoute = <Route path="/display/:shareToken" element={<PublicDisplayRoute />} />
// TODO24_PUBLIC_SIGN_ROUTE_END

export function AppRouter() {
  return (
    <Routes>
      {authRoutes}
      {publicSignRoute}
      {publicDisplayRoute}
      <Route path="*" element={<Navigate replace to="/login" />} />
    </Routes>
  )
}
