import "@testing-library/jest-dom/vitest"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, within } from "@testing-library/react"
import type { ReactNode } from "react"
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"
import { ToastProvider } from "../components/Toast.tsx"
import { AppRouter } from "../routes/AppRouter.tsx"
import { FullViewRoute } from "../routes/FullViewRoute.tsx"
import { PublicSignerRoute } from "../routes/PublicSignerRoute.tsx"

const routes = [
  "/login",
  "/boards",
  "/boards/new",
  "/boards/board-1/edit",
  "/boards/board-1/full",
  "/sign/share-1",
] as const

function renderRoute(route: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <ToastProvider>
          <LocationProbe />
          <AppRouter />
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function LocationProbe() {
  const location = useLocation()
  return <output data-testid="route-location">{location.pathname}</output>
}

function renderHandoff(route: string, path: string, element: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <Routes><Route element={element} path={path} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function setViewport(width: number) {
  Object.defineProperty(window, "innerWidth", { configurable: true, value: width })
}

describe.each(routes)("route boundary %s", (route) => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    setViewport(1024)
  })

  it("renders a deterministic loading boundary", () => {
    // Given
    vi.spyOn(globalThis, "fetch").mockImplementation(() => new Promise(() => undefined))

    // When
    renderRoute(route)

    // Then
    expect(screen.getByTestId("loading-view")).toBeInTheDocument()
  })

  it("renders a deterministic forbidden boundary", async () => {
    // Given
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 403 }))

    // When
    renderRoute(route)

    // Then
    expect(await screen.findByTestId("forbidden-view")).toBeInTheDocument()
  })

  it("renders a deterministic error boundary", async () => {
    // Given
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 500 }))

    // When
    renderRoute(route)

    // Then
    expect(await screen.findByTestId("error-view")).toBeInTheDocument()
  })
})

describe("public signing device support", () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    setViewport(1024)
  })

  it("renders the unsupported-device state without mounting a signer canvas on a phone", () => {
    // Given
    setViewport(390)
    const fetchSpy = vi.spyOn(globalThis, "fetch")

    // When
    renderRoute("/sign/share-1")

    // Then
    expect(screen.getByTestId("unsupported-device-view")).toBeInTheDocument()
    expect(screen.queryByTestId("signer-canvas")).not.toBeInTheDocument()
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})

describe("administrator board workflow", () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it("shows the board creation action when the server list is empty", async () => {
    // Given
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const path = input instanceof Request ? input.url : input.toString()
      if (path.endsWith("/api/v1/auth/session")) {
        return new Response(JSON.stringify({
          authenticated: true,
          expiresAt: "2099-01-01T00:00:00.000Z",
        }), { headers: { "Content-Type": "application/json" } })
      }
      return new Response(JSON.stringify([]), {
        headers: { "Content-Type": "application/json" },
      })
    })

    // When
    renderRoute("/boards")

    // Then
    expect(await screen.findByRole("link", { name: "새 보드 만들기" })).toBeInTheDocument()
    const sessionNavigation = screen.getByRole("navigation", { name: "관리자 세션" })
    expect(screen.getByRole("banner")).toContainElement(sessionNavigation)
    expect(within(sessionNavigation).getByRole("button", {
      name: "로그아웃",
    })).toBeInTheDocument()
  })

  it("retains the wildcard redirect to the login route", () => {
    // Given
    vi.spyOn(globalThis, "fetch").mockImplementation(() => new Promise(() => undefined))

    // When
    renderRoute("/unknown-route")

    // Then
    expect(screen.getByTestId("route-location")).toHaveTextContent("/login")
    expect(screen.getByTestId("loading-view")).toBeInTheDocument()
  })

  it("exports the exact full-view handoff module", async () => {
    // Given
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ status: "ready" }), {
      headers: { "Content-Type": "application/json" },
    }))

    // When
    renderHandoff("/boards/board-1/full", "/boards/:boardId/full", <FullViewRoute />)

    // Then
    expect(await screen.findByRole("heading", { name: "보드 전체보기" })).toBeInTheDocument()
    expect(screen.getByTestId("full-view-canvas")).toBeInTheDocument()
  })

  it("exports the exact public-signer handoff module", async () => {
    // Given
    setViewport(1024)
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(JSON.stringify({ state: "OPEN", title: "서명하기" }), {
        headers: { "Content-Type": "application/json" },
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ state: "SUBMITTED" }), {
        headers: { "Content-Type": "application/json" },
      }))

    // When
    renderHandoff("/sign/share-1", "/sign/:shareToken", <PublicSignerRoute />)

    // Then
    expect(await screen.findByRole("heading", { name: "서명하기" })).toBeInTheDocument()
    expect(screen.getByTestId("public-signer-complete")).toBeInTheDocument()
    expect(screen.queryByTestId("signer-canvas")).not.toBeInTheDocument()
  })
})
