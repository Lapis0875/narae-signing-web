import "@testing-library/jest-dom/vitest"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen } from "@testing-library/react"
import { MemoryRouter } from "react-router-dom"
import { afterEach, describe, expect, it, vi } from "vitest"
import { AppRouter } from "../routes/AppRouter.tsx"

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
        <AppRouter />
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
