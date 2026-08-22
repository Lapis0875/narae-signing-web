import "@testing-library/jest-dom/vitest"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { cleanup, render, screen, waitFor } from "@testing-library/react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { afterEach, expect, it, vi } from "vitest"
import { FullViewRoute } from "../../../routes/FullViewRoute.tsx"

const boardId = "00000000-0000-4000-8000-000000000001"

afterEach(() => { cleanup(); vi.restoreAllMocks() })

it("renders one control-free main landmark", async () => {
  // Given
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => input.toString().endsWith("/background")
    ? new Response(null, { status: 204 })
    : new Response(JSON.stringify({
      backgroundPresent: false,
      boardId,
      canvasHeight: 600,
      canvasWidth: 800,
      slots: [],
    }), { headers: { "Content-Type": "application/json" } }))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  // When
  const { container } = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/boards/${boardId}/full`]}>
        <Routes><Route element={<FullViewRoute />} path="/boards/:boardId/full" /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await screen.findByRole("img", { name: "서명 보드 전체보기" })

  // Then
  expect(container.querySelectorAll("main")).toHaveLength(1)
  expect(screen.queryByRole("button")).not.toBeInTheDocument()
  expect(screen.queryByRole("link")).not.toBeInTheDocument()
})

it("keeps loading after the snapshot resolves until the background resolves", async () => {
  // Given
  let resolveBackground = (_response: Response) => {}
  const background = new Promise<Response>((resolve) => { resolveBackground = resolve })
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => input.toString().endsWith("/background")
    ? background
    : new Response(JSON.stringify({
      backgroundPresent: false,
      boardId,
      canvasHeight: 600,
      canvasWidth: 800,
      slots: [],
    }), { headers: { "Content-Type": "application/json" } }))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  // When
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/boards/${boardId}/full`]}>
        <Routes><Route element={<FullViewRoute />} path="/boards/:boardId/full" /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await waitFor(() => expect(client.getQueryData(["admin", "boards", boardId, "snapshot"])).toBeDefined())

  // Then
  expect(screen.queryByRole("img", { name: "서명 보드 전체보기" })).not.toBeInTheDocument()
  resolveBackground(new Response(null, { status: 204 }))
  await screen.findByRole("img", { name: "서명 보드 전체보기" })
})
