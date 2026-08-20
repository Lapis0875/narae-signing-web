import { useQuery, useQueryClient } from "@tanstack/react-query"
import { useEffect, useState } from "react"
import { Navigate, useLocation, useNavigate } from "react-router-dom"
import { ApiError } from "../api/errors.ts"
import { AppShell } from "../components/AppShell.tsx"
import { ErrorView, ForbiddenView, LoadingView } from "../components/AsyncViews.tsx"
import {
  authSessionQueryKey,
  fetchAuthSession,
  login,
} from "../features/auth/authApi.ts"

const lockoutMs = 15 * 60_000

export function LoginRoute() {
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const session = useQuery({ queryFn: fetchAuthSession, queryKey: authSessionQueryKey })
  const [email, setEmail] = useState("")
  const [password, setPassword] = useState("")
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [failureMessage, setFailureMessage] = useState<string | null>(null)
  const [lockedUntil, setLockedUntil] = useState<number | null>(null)
  const [lockoutNow, setLockoutNow] = useState(Date.now())

  useEffect(() => {
    if (lockedUntil === null) {
      return
    }
    const remainingMs = lockedUntil - Date.now()
    const unlockTimer = window.setTimeout(() => setLockedUntil(null), Math.max(0, remainingMs))
    const minuteTimer = window.setInterval(() => setLockoutNow(Date.now()), 60_000)
    return () => {
      window.clearTimeout(unlockTimer)
      window.clearInterval(minuteTimer)
    }
  }, [lockedUntil])

  if (session.isPending) {
    return <LoadingView />
  }
  if (session.error instanceof ApiError && session.error.status === 403) {
    return <ForbiddenView />
  }
  if (session.error !== null) {
    return <ErrorView />
  }
  if (session.data.authenticated) {
    return <Navigate replace to="/boards" />
  }

  const remainingLockoutMinutes = lockedUntil === null
    ? null
    : Math.max(1, Math.ceil((lockedUntil - lockoutNow) / 60_000))
  const sessionExpired = typeof location.state === "object"
    && location.state !== null
    && "reason" in location.state
    && location.state.reason === "session-expired"

  return (
    <AppShell>
      <section>
        <h1>관리자 로그인</h1>
        {sessionExpired ? <p role="alert">세션이 만료되었습니다. 다시 로그인해 주세요.</p> : null}
        <form
          onSubmit={async (event) => {
            event.preventDefault()
            setIsSubmitting(true)
            setFailureMessage(null)
            try {
              const authenticated = await login(email, password)
              queryClient.setQueryData(authSessionQueryKey, authenticated)
              navigate("/boards", { replace: true })
            } catch (error) {
              setEmail("")
              setPassword("")
              if (error instanceof ApiError) {
                if (error.status === 429) {
                  setLockedUntil(Date.now() + lockoutMs)
                  setLockoutNow(Date.now())
                }
                setFailureMessage("로그인에 실패했습니다. 이메일과 비밀번호를 확인해 주세요.")
              } else {
                throw error
              }
            } finally {
              setIsSubmitting(false)
            }
          }}
        >
          <label htmlFor="admin-email">이메일</label>
          <input
            autoComplete="username"
            id="admin-email"
            name="email"
            onChange={(event) => setEmail(event.currentTarget.value)}
            required
            type="email"
            value={email}
          />
          <label htmlFor="admin-password">비밀번호</label>
          <input
            autoComplete="current-password"
            id="admin-password"
            name="password"
            onChange={(event) => setPassword(event.currentTarget.value)}
            required
            type="password"
            value={password}
          />
          <button disabled={isSubmitting || remainingLockoutMinutes !== null} type="submit">
            {isSubmitting ? "로그인 중" : "로그인"}
          </button>
        </form>
        {failureMessage === null ? null : (
          <p role="alert">
            {failureMessage}
            {remainingLockoutMinutes === null ? null : ` ${remainingLockoutMinutes}분 후 다시 시도해 주세요.`}
          </p>
        )}
      </section>
    </AppShell>
  )
}
