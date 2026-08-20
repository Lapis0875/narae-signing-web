import { useQuery, useQueryClient } from "@tanstack/react-query"
import { useEffect, useRef, useState } from "react"
import { Navigate, Outlet, useNavigate } from "react-router-dom"
import { ApiError } from "../api/errors.ts"
import { ErrorView, ForbiddenView, LoadingView } from "../components/AsyncViews.tsx"
import {
  authSessionQueryKey,
  fetchAuthSession,
  logout,
} from "../features/auth/authApi.ts"
import { SessionExpiryNotice } from "../features/auth/SessionExpiryNotice.tsx"

export function AdminRouteGuard() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const redirecting = useRef(false)
  const [isLoggingOut, setIsLoggingOut] = useState(false)
  const [logoutFailed, setLogoutFailed] = useState(false)
  const session = useQuery({ queryFn: fetchAuthSession, queryKey: authSessionQueryKey })

  useEffect(() => {
    const redirectOnUnauthorized = (error: unknown) => {
      if (!redirecting.current && error instanceof ApiError && error.status === 401) {
        redirecting.current = true
        queryClient.clear()
        navigate("/login", { replace: true, state: { reason: "session-expired" } })
      }
    }
    const unsubscribeQueries = queryClient.getQueryCache().subscribe((event) => {
      redirectOnUnauthorized(event.query.state.error)
    })
    const unsubscribeMutations = queryClient.getMutationCache().subscribe((event) => {
      redirectOnUnauthorized(event.mutation?.state.error)
    })
    return () => {
      unsubscribeQueries()
      unsubscribeMutations()
    }
  }, [navigate, queryClient])

  if (session.isPending) {
    return <LoadingView />
  }
  if (session.error instanceof ApiError && session.error.status === 403) {
    return <ForbiddenView />
  }
  if (session.error !== null) {
    return <ErrorView />
  }
  if (!session.data.authenticated) {
    return <Navigate replace to="/login" />
  }

  return (
    <>
      <SessionExpiryNotice expiresAt={session.data.expiresAt} />
      <button
        disabled={isLoggingOut}
        onClick={async () => {
          setIsLoggingOut(true)
          setLogoutFailed(false)
          try {
            await logout()
            queryClient.clear()
            navigate("/login", { replace: true })
          } catch (error) {
            setIsLoggingOut(false)
            if (error instanceof ApiError) {
              setLogoutFailed(true)
            } else {
              throw error
            }
          }
        }}
        type="button"
      >
        {isLoggingOut ? "로그아웃 중" : "로그아웃"}
      </button>
      {logoutFailed ? <p role="alert">로그아웃하지 못했습니다. 다시 시도해 주세요.</p> : null}
      <Outlet />
    </>
  )
}
