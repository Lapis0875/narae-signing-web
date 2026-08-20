import { useQuery } from "@tanstack/react-query"
import type { ReactNode } from "react"
import { z } from "zod"
import { apiRequest } from "../api/client.ts"
import { ApiError } from "../api/errors.ts"
import { AppShell } from "../components/AppShell.tsx"
import {
  AuthGuard,
  ErrorView,
  ForbiddenView,
  LoadingView,
} from "../components/AsyncViews.tsx"

const routeResponseSchema = z.object({ status: z.literal("ready") })

type RouteShellProps = {
  readonly admin?: boolean
  readonly children?: ReactNode
  readonly endpoint: string
  readonly title: string
}

export function RouteShell({ admin = false, children, endpoint, title }: RouteShellProps) {
  const query = useQuery({
    queryFn: async () => routeResponseSchema.parse(await apiRequest(endpoint)),
    queryKey: ["route-shell", endpoint],
  })

  const content = (
    <AppShell>
      <section>
        <h1>{title}</h1>
        {children}
      </section>
    </AppShell>
  )

  if (admin) {
    return (
      <AuthGuard error={query.error} isPending={query.isPending}>
        {content}
      </AuthGuard>
    )
  }
  if (query.isPending) {
    return <LoadingView />
  }
  if (query.error instanceof ApiError && query.error.status === 403) {
    return <ForbiddenView />
  }
  if (query.error !== null) {
    return <ErrorView />
  }
  return content
}
