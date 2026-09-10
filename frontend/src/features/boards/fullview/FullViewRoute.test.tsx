import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import {
  createMemoryRouter,
  MemoryRouter,
  Route,
  RouterProvider,
  Routes,
} from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { FullViewRoute } from "../../../routes/FullViewRoute.tsx";

const boardId = "00000000-0000-4000-8000-000000000001";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

it("renders one control-free main landmark", async () => {
  // Given
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) =>
    input.toString().endsWith("/background")
      ? new Response(null, { status: 204 })
      : new Response(
          JSON.stringify({
            backgroundPresent: false,
            boardId,
            canvasHeight: 600,
            canvasWidth: 800,
            signatureInkColor: "black",
            slots: [],
          }),
          { headers: { "Content-Type": "application/json" } },
        ),
  );
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  // When
  const { container } = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/boards/${boardId}/full`]}>
        <Routes>
          <Route element={<FullViewRoute />} path="/boards/:boardId/full" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  await screen.findByRole("img", { name: "서명 보드 전체보기" });

  // Then
  expect(container.querySelectorAll("main")).toHaveLength(1);
  expect(screen.queryByRole("button")).not.toBeInTheDocument();
  expect(screen.queryByRole("link")).not.toBeInTheDocument();
});

it("renders a snapshot slot without a background decoration contract", async () => {
  // Given
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) =>
    input.toString().endsWith("/background")
      ? new Response(null, { status: 204 })
      : new Response(
          JSON.stringify({
            backgroundPresent: false,
            boardId,
            canvasHeight: 600,
            canvasWidth: 800,
            signatureInkColor: "black",
            slots: [
              {
                draftSignature: null,
                height: 0.2,
                id: "00000000-0000-4000-8000-000000000002",
                signature: null,
                width: 0.3,
                x: 0.1,
                y: 0.2,
              },
            ],
          }),
          { headers: { "Content-Type": "application/json" } },
        ),
  );
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  // When
  const { container } = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/boards/${boardId}/full`]}>
        <Routes>
          <Route element={<FullViewRoute />} path="/boards/:boardId/full" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  await screen.findByRole("img", { name: "서명 보드 전체보기" });

  // Then
  expect(container.querySelector("[data-background]")).not.toBeInTheDocument();
});

it("keeps loading after the snapshot resolves until the background resolves", async () => {
  // Given
  let resolveBackground = (_response: Response) => {};
  const background = new Promise<Response>((resolve) => {
    resolveBackground = resolve;
  });
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) =>
    input.toString().endsWith("/background")
      ? background
      : new Response(
          JSON.stringify({
            backgroundPresent: false,
            boardId,
            canvasHeight: 600,
            canvasWidth: 800,
            signatureInkColor: "black",
            slots: [],
          }),
          { headers: { "Content-Type": "application/json" } },
        ),
  );
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  // When
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/boards/${boardId}/full`]}>
        <Routes>
          <Route element={<FullViewRoute />} path="/boards/:boardId/full" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  await waitFor(() =>
    expect(
      client.getQueryData(["admin", "boards", boardId, "snapshot"]),
    ).toBeDefined(),
  );

  // Then
  expect(
    screen.queryByRole("img", { name: "서명 보드 전체보기" }),
  ).not.toBeInTheDocument();
  resolveBackground(new Response(null, { status: 204 }));
  await screen.findByRole("img", { name: "서명 보드 전체보기" });
});

it("refreshes the full view snapshot and background every five seconds", async () => {
  // Given
  vi.useFakeTimers();
  let backgroundRequests = 0;
  let snapshotRequests = 0;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    if (input.toString().endsWith("/background")) {
      backgroundRequests += 1;
      return new Response(null, { status: 204 });
    }
    snapshotRequests += 1;
    return new Response(
      JSON.stringify({
        backgroundPresent: false,
        boardId,
        canvasHeight: 600,
        canvasWidth: 800,
        signatureInkColor: "black",
        slots: [],
      }),
      { headers: { "Content-Type": "application/json" } },
    );
  });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  // When
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/boards/${boardId}/full`]}>
        <Routes>
          <Route element={<FullViewRoute />} path="/boards/:boardId/full" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(5_000);
  });

  // Then
  expect(snapshotRequests).toBe(2);
  expect(backgroundRequests).toBe(2);
});

it("ignores a delayed snapshot from the previous board after navigation", async () => {
  // Given
  const nextBoardId = "00000000-0000-4000-8000-000000000003";
  let resolvePreviousSnapshot = (_response: Response) => {};
  const previousSnapshot = new Promise<Response>((resolve) => {
    resolvePreviousSnapshot = resolve;
  });
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      disconnect() {}
    },
  );
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = input.toString();
    if (url.endsWith("/background")) return new Response(null, { status: 204 });
    if (url.includes(boardId)) return previousSnapshot;
    return Response.json({
      backgroundPresent: false,
      boardId: nextBoardId,
      canvasHeight: 600,
      canvasWidth: 800,
      signatureInkColor: "white",
      slots: [
        {
          draftEpoch: 1,
          draftSignature: {
            strokes: [
              {
                points: [
                  { x: 100_000, y: 200_000 },
                  { x: 900_000, y: 800_000 },
                ],
              },
            ],
            version: 1,
          },
          height: 1,
          id: "00000000-0000-4000-8000-000000000002",
          revision: 1,
          signature: null,
          width: 1,
          x: 0,
          y: 0,
        },
      ],
    });
  });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createMemoryRouter(
    [{ element: <FullViewRoute />, path: "/boards/:boardId/full" }],
    {
      initialEntries: [`/boards/${boardId}/full`],
    },
  );
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );

  // When
  await act(async () => {
    await router.navigate(`/boards/${nextBoardId}/full`);
  });
  await screen.findByTestId("submitted-signature");
  resolvePreviousSnapshot(
    Response.json({
      backgroundPresent: false,
      boardId,
      canvasHeight: 600,
      canvasWidth: 800,
      signatureInkColor: "black",
      slots: [],
    }),
  );
  await act(async () => {
    await previousSnapshot;
  });

  // Then
  expect(screen.getByTestId("submitted-signature")).toBeVisible();
  expect(
    client.getQueryData(["admin", "boards", nextBoardId, "snapshot"]),
  ).toMatchObject({
    boardId: nextBoardId,
    signatureInkColor: "white",
  });
});
