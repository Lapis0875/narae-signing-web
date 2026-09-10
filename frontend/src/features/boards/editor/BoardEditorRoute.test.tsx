import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import type { ReactNode } from "react";
import { Link, MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setCsrfToken } from "../../../api/client.ts";
import { ToastProvider } from "../../../components/Toast.tsx";
import type { Board } from "../list/boardApi.ts";
import { BoardEditorRoute } from "./BoardEditorRoute.tsx";
import { SerializedSaveQueue } from "./saveQueue.ts";

const boardId = "11111111-1111-4111-8111-111111111111";
const board = {
  canvasHeight: 1080,
  canvasWidth: 1920,
  createdAt: "2026-08-21T00:00:00.000Z",
  id: boardId,
  shareLinkVersion: 1,
  signatureInkColor: "black",
  status: "설정 중",
  title: "가을 서명 발표회",
  updatedAt: "2026-08-21T00:00:00.000Z",
} as const;
const roster = [
  {
    id: "31111111-1111-4111-8111-111111111111",
    identity: { job: "담당", name: "한별", organization: "나래" },
    slot: {
      height: 0.2,
      id: "21111111-1111-4111-8111-111111111111",
      placementStatus: "PLACED",
      revision: 1,
      width: 0.2,
      x: 0,
      y: 0,
    },
    submitted: false,
  },
  {
    id: "32222222-2222-4222-8222-222222222222",
    identity: { job: "담당", name: "누리", organization: "나래" },
    slot: {
      height: null,
      id: "22222222-2222-4222-8222-222222222222",
      placementStatus: "UNPLACED",
      revision: 0,
      width: null,
      x: null,
      y: null,
    },
    submitted: false,
  },
] as const;

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.setAttribute("open", "");
    },
  });
  Object.defineProperty(HTMLDialogElement.prototype, "close", {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.removeAttribute("open");
    },
  });
});

afterEach(() => {
  cleanup();
  setCsrfToken(null);
  document.cookie = "XSRF-TOKEN=; Max-Age=0; Path=/";
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  Reflect.deleteProperty(HTMLDialogElement.prototype, "close");
  Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal");
});

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    headers: { "Content-Type": "application/json" },
    status,
  });
}

async function setCsrfAfterReads(client: QueryClient): Promise<void> {
  await waitFor(() => {
    if (client.isFetching() !== 0) {
      throw new TypeError();
    }
  });
  setCsrfToken("csrf-test");
}

function renderEditor(
  background: () => Response | Promise<Response>,
  status: Board["status"] = board.status,
  actionExtensions?: ReactNode,
  forceDisplay: () => Response = () =>
    json({ code: "SERVICE_UNAVAILABLE" }, 503),
) {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const path = input.toString();
    if (path.endsWith("/background")) return background();
    if (path.endsWith("/roster")) return json(roster);
    if (path.endsWith("/share"))
      return json({ shareToken: "safe-share", version: 1 });
    if (path.endsWith("/display/force-replace")) return forceDisplay();
    return json({ ...board, status });
  });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
        <ToastProvider>
          <Routes>
            <Route
              element={
                actionExtensions === undefined ? (
                  <BoardEditorRoute />
                ) : (
                  <BoardEditorRoute actionExtensions={actionExtensions} />
                )
              }
              path="/boards/:boardId/edit"
            />
          </Routes>
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("BoardEditorRoute", () => {
  it("Given an active white-ink board When controls render Then the selector stays visible and locked", async () => {
    // Given
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(null, { status: 204 });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      return json({
        ...board,
        signatureInkColor: "white",
        status: "서명 진행",
      });
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    // When
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    // Then
    expect(await screen.findByRole("radio", { name: "흰색" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "검정" })).toBeDisabled();
    expect(screen.getByRole("radio", { name: "흰색" })).toBeDisabled();
    expect(
      screen.getByText("서명이 시작된 뒤에는 색상을 변경할 수 없습니다."),
    ).toBeVisible();
  });

  it("Given a deferred layout save When start is requested Then transition waits until the queue is idle", async () => {
    // Given
    let releaseSlot: () => void = () => undefined;
    const slotGate = new Promise<Response>((resolve) => {
      releaseSlot = () =>
        resolve(
          json({
            boardId,
            bounds: { height: 0.2, width: 0.2, x: 0.2, y: 0.2 },
            id: roster[1].slot.id,
            revision: 1,
            rosterEntryId: roster[1].id,
            signaturePresent: false,
            submitted: false,
          }),
        );
    });
    let openRequests = 0;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(null, { status: 204 });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (path.includes("/slots/") && init?.method === "PATCH") return slotGate;
      if (path.endsWith("/open")) {
        openRequests += 1;
        return json({ ...board, status: "서명 진행" });
      }
      return json(board);
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    const start = await screen.findByRole("button", { name: "서명 시작" });

    // When
    await setCsrfAfterReads(client);
    fireEvent.click(screen.getByRole("button", { name: "누리 배치" }));
    fireEvent.click(start);

    // Then
    expect(start).toBeDisabled();
    expect(openRequests).toBe(0);
    releaseSlot();
    await waitFor(() => expect(start).toBeEnabled());
    await setCsrfAfterReads(client);
    fireEvent.click(start);
    await waitFor(() => expect(openRequests).toBe(1));
  });

  it("Given a layout save is unresolved When color saving succeeds Then the status stays saving until layout succeeds", async () => {
    // Given
    let releaseSlot: () => void = () => undefined;
    let slotRequests = 0;
    let slotResponses = 0;
    let serverColor: Board["signatureInkColor"] = "black";
    const slotGate = new Promise<Response>((resolve) => {
      releaseSlot = () =>
        resolve(
          json({
            boardId,
            bounds: { height: 0.2, width: 0.2, x: 0, y: 0.01 },
            id: roster[0].slot.id,
            revision: 2,
            rosterEntryId: roster[0].id,
            signaturePresent: false,
            submitted: false,
          }),
        );
    });
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(new Uint8Array([1]), {
          headers: { "Content-Type": "image/png" },
        });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (path.includes("/slots/") && init?.method === "PATCH") {
        slotRequests += 1;
        const response = await slotGate;
        slotResponses += 1;
        return response;
      }
      if (path.endsWith(`/boards/${boardId}`) && init?.method === "PATCH") {
        serverColor = "white";
        return json({ ...board, signatureInkColor: serverColor });
      }
      return json({ ...board, signatureInkColor: serverColor });
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByRole("application", { name: "서명 보드 캔버스" });
    await setCsrfAfterReads(client);
    const saveStatus = screen.getByLabelText("자동 저장 상태");
    const start = screen.getByRole("button", { name: "서명 시작" });

    // When
    fireEvent.keyDown(screen.getByRole("button", { name: "한별 이동" }), {
      key: "ArrowDown",
    });
    await waitFor(() => expect(slotRequests).toBe(1));
    expect(saveStatus).toHaveTextContent("저장 중");
    expect(start).toBeDisabled();
    fireEvent.click(screen.getByRole("radio", { name: "흰색" }));
    await waitFor(() =>
      expect(screen.getByRole("radio", { name: "흰색" })).toBeEnabled(),
    );

    // Then
    expect(screen.getByRole("radio", { name: "흰색" })).toBeChecked();
    expect(serverColor).toBe("white");
    expect(slotResponses).toBe(0);
    expect(saveStatus).toHaveTextContent("저장 중");
    expect(saveStatus).not.toHaveTextContent("저장됨");
    expect(start).toBeDisabled();
    releaseSlot();
    await waitFor(() => expect(slotResponses).toBe(1));
    await waitFor(() => expect(saveStatus).toHaveTextContent("저장됨"));
    expect(start).toBeEnabled();
  });

  it.each([
    { expectedStatus: "저장됨", olderStatus: 200 },
    { expectedStatus: "저장 실패", olderStatus: 500 },
  ])(
    "Given a debounced move and newer unplace When the older slot response is held Then status tracks both attempts ($olderStatus)",
    async ({ expectedStatus, olderStatus }) => {
      // Given an accepted debounced move followed by a successful immediate unplace
      let deleteResponses = 0;
      let patchRequests = 0;
      const patchResponses: number[] = [];
      let releaseOlderPatch: () => void = () => undefined;
      const olderPatchGate = new Promise<Response>((resolve) => {
        releaseOlderPatch = () => {
          patchResponses.push(olderStatus);
          resolve(
            olderStatus === 200
              ? json({
                  boardId,
                  bounds: { height: 0.2, width: 0.2, x: 0, y: 0.01 },
                  id: roster[0].slot.id,
                  revision: 2,
                  rosterEntryId: roster[0].id,
                  signaturePresent: false,
                  submitted: false,
                })
              : json({ code: "UNKNOWN" }, 500),
          );
        };
      });
      vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
      vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
      vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
        const path = input.toString();
        if (path.endsWith("/background"))
          return new Response(new Uint8Array([1]), {
            headers: { "Content-Type": "image/png" },
          });
        if (path.endsWith("/roster")) return json(roster);
        if (path.endsWith("/share"))
          return json({ shareToken: "safe-share", version: 1 });
        if (path.includes("/slots/") && init?.method === "DELETE") {
          deleteResponses += 1;
          return new Response(null, { status: 204 });
        }
        if (path.includes("/slots/") && init?.method === "PATCH") {
          patchRequests += 1;
          return olderPatchGate;
        }
        return json(board);
      });
      const client = new QueryClient({
        defaultOptions: { queries: { retry: false } },
      });
      render(
        <QueryClientProvider client={client}>
          <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
            <ToastProvider>
              <Routes>
                <Route
                  element={<BoardEditorRoute />}
                  path="/boards/:boardId/edit"
                />
              </Routes>
            </ToastProvider>
          </MemoryRouter>
        </QueryClientProvider>,
      );
      await screen.findByRole("application", { name: "서명 보드 캔버스" });
      await setCsrfAfterReads(client);
      Reflect.set(document, "cookie", "XSRF-TOKEN=csrf-test; Path=/");
      const saveStatus = screen.getByLabelText("자동 저장 상태");
      const start = screen.getByRole("button", { name: "서명 시작" });

      // When the newer DELETE settles but the older accepted PATCH has no response
      fireEvent.keyDown(screen.getByRole("button", { name: "한별 이동" }), {
        key: "ArrowDown",
      });
      fireEvent.click(screen.getByRole("button", { name: "한별 배치 해제" }));
      await waitFor(() => expect(deleteResponses).toBe(1));
      await waitFor(() => expect(patchRequests).toBe(1));

      // Then the toolbar must not claim all same-owner work is saved
      expect(patchResponses).toEqual([]);
      expect(saveStatus).toHaveTextContent("저장 중");
      expect(saveStatus).not.toHaveTextContent("저장됨");
      expect(start).toBeDisabled();

      // And only the older accepted write owns its terminal result
      releaseOlderPatch();
      await waitFor(() => expect(patchResponses).toEqual([olderStatus]));
      await waitFor(() => expect(saveStatus).toHaveTextContent(expectedStatus));
    },
  );

  it("Given two coalesced moves for one slot When the only deliverable PATCH settles Then no replaced attempt remains pending", async () => {
    let patchRequests = 0;
    let releasePatch: () => void = () => undefined;
    const patchGate = new Promise<Response>((resolve) => {
      releasePatch = () =>
        resolve(
          json({
            boardId,
            bounds: { height: 0.2, width: 0.2, x: 0, y: 0.02 },
            id: roster[0].slot.id,
            revision: 2,
            rosterEntryId: roster[0].id,
            signaturePresent: false,
            submitted: false,
          }),
        );
    });
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(new Uint8Array([1]), {
          headers: { "Content-Type": "image/png" },
        });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (path.includes("/slots/") && init?.method === "PATCH") {
        patchRequests += 1;
        return patchGate;
      }
      return json(board);
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByRole("application", { name: "서명 보드 캔버스" });
    await setCsrfAfterReads(client);
    const move = screen.getByRole("button", { name: "한별 이동" });
    const saveStatus = screen.getByLabelText("자동 저장 상태");

    fireEvent.keyDown(move, { key: "ArrowDown" });
    fireEvent.keyDown(move, { key: "ArrowDown" });
    await waitFor(() => expect(patchRequests).toBe(1));
    expect(saveStatus).toHaveTextContent("저장 중");
    releasePatch();
    await waitFor(() => expect(saveStatus).toHaveTextContent("저장됨"));
    expect(screen.getByRole("button", { name: "서명 시작" })).toBeEnabled();
  });

  it("Given A1 flushed behind held B When A2 succeeds after A1 fails Then A1 failure survives until a later retry", async () => {
    // Given two placed slots and a first slot-B write holding the serial queue
    const placedRoster = [
      roster[0],
      {
        ...roster[1],
        slot: {
          height: 0.2,
          id: roster[1].slot.id,
          placementStatus: "PLACED" as const,
          revision: 1,
          width: 0.2,
          x: 0.3,
          y: 0.3,
        },
      },
    ];
    let releaseB: () => void = () => undefined;
    let releaseA2: () => void = () => undefined;
    const bGate = new Promise<Response>((resolve) => {
      releaseB = () =>
        resolve(
          json({
            boardId,
            bounds: { height: 0.2, width: 0.2, x: 0.3, y: 0.31 },
            id: roster[1].slot.id,
            revision: 2,
            rosterEntryId: roster[1].id,
            signaturePresent: false,
            submitted: false,
          }),
        );
    });
    const a2Gate = new Promise<Response>((resolve) => {
      releaseA2 = () =>
        resolve(
          json({
            boardId,
            bounds: { height: 0.2, width: 0.2, x: 0, y: 0.02 },
            id: roster[0].slot.id,
            revision: 3,
            rosterEntryId: roster[0].id,
            signaturePresent: false,
            submitted: false,
          }),
        );
    });
    const patchRequests: string[] = [];
    const patchResponses: number[] = [];
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(new Uint8Array([1]), {
          headers: { "Content-Type": "image/png" },
        });
      if (path.endsWith("/roster")) return json(placedRoster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (path.includes("/slots/") && init?.method === "PATCH") {
        const slotId = path.slice(path.lastIndexOf("/") + 1);
        patchRequests.push(slotId);
        if (slotId === roster[1].slot.id) {
          const response = await bGate;
          patchResponses.push(response.status);
          return response;
        }
        if (
          patchRequests.filter((request) => request === roster[0].slot.id)
            .length === 1
        ) {
          patchResponses.push(500);
          return json({ code: "UNKNOWN" }, 500);
        }
        if (
          patchRequests.filter((request) => request === roster[0].slot.id)
            .length === 2
        ) {
          const response = await a2Gate;
          patchResponses.push(response.status);
          return response;
        }
        patchResponses.push(200);
        return json({
          boardId,
          bounds: { height: 0.2, width: 0.2, x: 0, y: 0.03 },
          id: roster[0].slot.id,
          revision: 4,
          rosterEntryId: roster[0].id,
          signaturePresent: false,
          submitted: false,
        });
      }
      return json(board);
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByRole("application", { name: "서명 보드 캔버스" });
    await setCsrfAfterReads(client);
    Reflect.set(document, "cookie", "XSRF-TOKEN=csrf-test; Path=/");
    const saveStatus = screen.getByLabelText("자동 저장 상태");
    const start = screen.getByRole("button", { name: "서명 시작" });
    const moveA = screen.getByRole("button", { name: "한별 이동" });
    const flush = vi.spyOn(SerializedSaveQueue.prototype, "flush");
    const enqueue = vi.spyOn(SerializedSaveQueue.prototype, "enqueue");

    // When B starts, A1 flushes into its tail, and A2 is accepted afterward
    fireEvent.keyDown(screen.getByRole("button", { name: "누리 이동" }), {
      key: "ArrowDown",
    });
    await waitFor(() => expect(patchRequests).toEqual([roster[1].slot.id]));
    fireEvent.keyDown(moveA, { key: "ArrowDown" });
    await waitFor(() =>
      expect(
        flush.mock.calls.filter(([target]) => target === roster[0].slot.id),
      ).toHaveLength(1),
    );
    fireEvent.keyDown(moveA, { key: "ArrowDown" });
    await waitFor(() =>
      expect(
        flush.mock.calls.filter(([target]) => target === roster[0].slot.id),
      ).toHaveLength(2),
    );
    expect(patchRequests).toEqual([roster[1].slot.id]);
    expect(enqueue).toHaveBeenCalledTimes(3);

    // And A1 actually delivers and fails before the already-accepted A2 succeeds
    releaseB();
    await waitFor(() => expect(patchRequests).toHaveLength(3));
    expect(patchResponses).toEqual([200, 500]);
    expect(saveStatus).toHaveTextContent("저장 실패");
    expect(start).toBeDisabled();
    releaseA2();
    await waitFor(() => expect(patchResponses).toEqual([200, 500, 200]));

    // Then A2 cannot erase A1's failure because it began before that failure
    expect(saveStatus).toHaveTextContent("저장 실패");
    expect(saveStatus).not.toHaveTextContent("저장됨");
    expect(start).toBeEnabled();

    // And only a genuinely later retry by the same owner clears the failure
    fireEvent.keyDown(moveA, { key: "ArrowDown" });
    await waitFor(() => expect(patchRequests).toHaveLength(4));
    await waitFor(() => expect(saveStatus).toHaveTextContent("저장됨"));
  });

  it("Given layout saving fails before color settles When color later succeeds Then the failure stays visible", async () => {
    // Given
    let releaseSlotFailure: () => void = () => undefined;
    let releaseColor: () => void = () => undefined;
    let serverColor: Board["signatureInkColor"] = "black";
    const slotGate = new Promise<Response>((resolve) => {
      releaseSlotFailure = () => resolve(json({ code: "UNKNOWN" }, 500));
    });
    const colorGate = new Promise<Response>((resolve) => {
      releaseColor = () => {
        serverColor = "white";
        resolve(json({ ...board, signatureInkColor: serverColor }));
      };
    });
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(new Uint8Array([1]), {
          headers: { "Content-Type": "image/png" },
        });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (path.includes("/slots/") && init?.method === "PATCH") return slotGate;
      if (path.endsWith(`/boards/${boardId}`) && init?.method === "PATCH")
        return colorGate;
      return json({ ...board, signatureInkColor: serverColor });
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByRole("application", { name: "서명 보드 캔버스" });
    await setCsrfAfterReads(client);
    const saveStatus = screen.getByLabelText("자동 저장 상태");

    // When
    fireEvent.keyDown(screen.getByRole("button", { name: "한별 이동" }), {
      key: "ArrowDown",
    });
    await waitFor(() => expect(saveStatus).toHaveTextContent("저장 중"));
    fireEvent.click(screen.getByRole("radio", { name: "흰색" }));
    releaseSlotFailure();
    await waitFor(() => expect(saveStatus).toHaveTextContent("저장 실패"));
    releaseColor();
    await waitFor(() =>
      expect(screen.getByRole("radio", { name: "흰색" })).toBeEnabled(),
    );

    // Then
    expect(screen.getByRole("radio", { name: "흰색" })).toBeChecked();
    expect(saveStatus).toHaveTextContent("저장 실패");
    expect(saveStatus).not.toHaveTextContent("저장됨");
    expect(
      screen.getByText(
        "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
      ),
    ).toBeVisible();
  });

  it("Given layout saving has already failed When color saving succeeds Then only a successful layout retry clears the failure", async () => {
    // Given
    let slotRequests = 0;
    let colorRequests = 0;
    let serverColor: Board["signatureInkColor"] = "black";
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(new Uint8Array([1]), {
          headers: { "Content-Type": "image/png" },
        });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (path.includes("/slots/") && init?.method === "PATCH") {
        slotRequests += 1;
        return slotRequests === 1
          ? json({ code: "UNKNOWN" }, 500)
          : json({
              boardId,
              bounds: { height: 0.2, width: 0.2, x: 0, y: 0.02 },
              id: roster[0].slot.id,
              revision: 2,
              rosterEntryId: roster[0].id,
              signaturePresent: false,
              submitted: false,
            });
      }
      if (
        init?.method === "PATCH" &&
        typeof init.body === "string" &&
        init.body.includes("signatureInkColor")
      ) {
        colorRequests += 1;
        serverColor = "white";
        return json({ ...board, signatureInkColor: serverColor });
      }
      return json({ ...board, signatureInkColor: serverColor });
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByRole("application", { name: "서명 보드 캔버스" });
    await setCsrfAfterReads(client);
    const moveButton = screen.getByRole("button", { name: "한별 이동" });
    const saveStatus = screen.getByLabelText("자동 저장 상태");

    // When the first layout save has visibly failed
    fireEvent.keyDown(moveButton, { key: "ArrowDown" });
    await waitFor(() => expect(slotRequests).toBe(1));
    await waitFor(() => expect(saveStatus).toHaveTextContent("저장 실패"));
    const failureToast = screen.getByText(
      "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
    );
    expect(failureToast).toBeVisible();
    setCsrfToken("csrf-test");

    // And an unrelated color save succeeds and settles
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "서명 시작" })).toBeEnabled(),
    );
    fireEvent.keyDown(screen.getByRole("radio", { name: "검정" }), {
      key: "ArrowRight",
    });
    await waitFor(() => expect(colorRequests).toBe(1));
    await waitFor(() =>
      expect(screen.getByRole("radio", { name: "흰색" })).toBeEnabled(),
    );

    // Then the unretried layout failure remains owned by the layout save
    expect(screen.getByRole("radio", { name: "흰색" })).toBeChecked();
    expect(serverColor).toBe("white");
    expect(slotRequests).toBe(1);
    expect(saveStatus).toHaveTextContent("저장 실패");
    expect(saveStatus).not.toHaveTextContent("저장됨");
    expect(failureToast).toBeVisible();

    // And only a relevant successful layout retry clears it
    setCsrfToken("csrf-test");
    fireEvent.keyDown(screen.getByRole("button", { name: "한별 이동" }), {
      key: "ArrowDown",
    });
    await waitFor(() => expect(slotRequests).toBe(2));
    await waitFor(() => expect(saveStatus).toHaveTextContent("저장됨"));
  });

  it("Given title saving has already failed When color saving succeeds Then the title failure stays visible", async () => {
    // Given
    let titleRequests = 0;
    let colorRequests = 0;
    let serverColor: Board["signatureInkColor"] = "black";
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(new Uint8Array([1]), {
          headers: { "Content-Type": "image/png" },
        });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (path.endsWith(`/boards/${boardId}`) && init?.method === "PATCH") {
        if (
          typeof init.body === "string" &&
          init.body.includes("signatureInkColor")
        ) {
          colorRequests += 1;
          serverColor = "white";
          return json({ ...board, signatureInkColor: serverColor });
        }
        titleRequests += 1;
        return json({ code: "UNKNOWN" }, 500);
      }
      return json({ ...board, signatureInkColor: serverColor });
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByRole("application", { name: "서명 보드 캔버스" });
    await setCsrfAfterReads(client);
    const saveStatus = screen.getByLabelText("자동 저장 상태");

    // When the title save fails before color saving starts
    fireEvent.change(screen.getByRole("textbox", { name: "보드 제목" }), {
      target: { value: "실패할 제목" },
    });
    fireEvent.click(screen.getByRole("button", { name: "제목 저장" }));
    await waitFor(() => expect(titleRequests).toBe(1));
    await waitFor(() => expect(saveStatus).toHaveTextContent("저장 실패"));
    const failureToast = screen.getByText(
      "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
    );
    setCsrfToken("csrf-test");

    // And an unrelated color save succeeds and settles
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "서명 시작" })).toBeEnabled(),
    );
    fireEvent.keyDown(screen.getByRole("radio", { name: "검정" }), {
      key: "ArrowRight",
    });
    await waitFor(() => expect(colorRequests).toBe(1));
    await waitFor(() =>
      expect(screen.getByRole("radio", { name: "흰색" })).toBeEnabled(),
    );

    // Then no title retry occurred and its failure remains visible
    expect(screen.getByRole("radio", { name: "흰색" })).toBeChecked();
    expect(titleRequests).toBe(1);
    expect(saveStatus).toHaveTextContent("저장 실패");
    expect(saveStatus).not.toHaveTextContent("저장됨");
    expect(failureToast).toBeVisible();
  });

  it("Given title saving is unresolved When color saving succeeds Then the status stays saving until title succeeds", async () => {
    // Given
    let releaseColor: () => void = () => undefined;
    let releaseTitle: () => void = () => undefined;
    let colorRequests = 0;
    let titleRequests = 0;
    let titleResponses = 0;
    let serverColor: Board["signatureInkColor"] = "black";
    const colorGate = new Promise<Response>((resolve) => {
      releaseColor = () => {
        serverColor = "white";
        resolve(json({ ...board, signatureInkColor: serverColor }));
      };
    });
    const titleGate = new Promise<Response>((resolve) => {
      releaseTitle = () => resolve(json({ ...board, title: "수정된 제목" }));
    });
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(new Uint8Array([1]), {
          headers: { "Content-Type": "image/png" },
        });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (path.endsWith(`/boards/${boardId}`) && init?.method === "PATCH") {
        if (
          typeof init.body === "string" &&
          init.body.includes("signatureInkColor")
        ) {
          colorRequests += 1;
          return colorGate;
        }
        titleRequests += 1;
        const response = await titleGate;
        titleResponses += 1;
        return response;
      }
      return json({ ...board, signatureInkColor: serverColor });
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByRole("application", { name: "서명 보드 캔버스" });
    await setCsrfAfterReads(client);
    const saveStatus = screen.getByLabelText("자동 저장 상태");

    // When
    fireEvent.click(screen.getByRole("radio", { name: "흰색" }));
    await waitFor(() => expect(colorRequests).toBe(1));
    fireEvent.change(screen.getByRole("textbox", { name: "보드 제목" }), {
      target: { value: "수정된 제목" },
    });
    fireEvent.click(screen.getByRole("button", { name: "제목 저장" }));
    await waitFor(() => expect(titleRequests).toBe(1));
    releaseColor();
    await waitFor(() =>
      expect(screen.getByRole("radio", { name: "흰색" })).toBeChecked(),
    );

    // Then
    expect(titleResponses).toBe(0);
    expect(saveStatus).toHaveTextContent("저장 중");
    expect(saveStatus).not.toHaveTextContent("저장됨");
    expect(screen.getByRole("button", { name: "서명 시작" })).toBeDisabled();
    releaseTitle();
    await waitFor(() => expect(titleResponses).toBe(1));
    await waitFor(() => expect(saveStatus).toHaveTextContent("저장됨"));
    expect(screen.getByRole("button", { name: "서명 시작" })).toBeEnabled();
  });

  it.each([
    { expectedColor: "white", expectedStatus: "저장됨", retryStatus: 200 },
    { expectedColor: "black", expectedStatus: "저장 실패", retryStatus: 500 },
  ])(
    "Given color failed and layout recovered When a color retry is held and title succeeds Then status waits for color $retryStatus",
    async ({ expectedColor, expectedStatus, retryStatus }) => {
      // Given a failed color attempt followed by a successful, unrelated layout write
      let colorRequests = 0;
      const colorResponses: number[] = [];
      let layoutRequests = 0;
      let titleRequests = 0;
      let releaseColorRetry: () => void = () => undefined;
      let serverColor: Board["signatureInkColor"] = "black";
      const colorRetryGate = new Promise<Response>((resolve) => {
        releaseColorRetry = () => {
          colorResponses.push(retryStatus);
          if (retryStatus === 200) serverColor = "white";
          resolve(
            retryStatus === 200
              ? json({ ...board, signatureInkColor: serverColor })
              : json({ code: "UNKNOWN" }, 500),
          );
        };
      });
      vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
      vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
      vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
        const path = input.toString();
        if (path.endsWith("/background"))
          return new Response(new Uint8Array([1]), {
            headers: { "Content-Type": "image/png" },
          });
        if (path.endsWith("/roster")) return json(roster);
        if (path.endsWith("/share"))
          return json({ shareToken: "safe-share", version: 1 });
        if (path.includes("/slots/") && init?.method === "PATCH") {
          layoutRequests += 1;
          return json({
            boardId,
            bounds: { height: 0.2, width: 0.2, x: 0, y: 0.01 },
            id: roster[0].slot.id,
            revision: 2,
            rosterEntryId: roster[0].id,
            signaturePresent: false,
            submitted: false,
          });
        }
        if (path.endsWith(`/boards/${boardId}`) && init?.method === "PATCH") {
          if (
            typeof init.body === "string" &&
            init.body.includes("signatureInkColor")
          ) {
            colorRequests += 1;
            if (colorRequests === 1) {
              colorResponses.push(500);
              return json({ code: "UNKNOWN" }, 500);
            }
            return colorRetryGate;
          }
          titleRequests += 1;
          return json({
            ...board,
            signatureInkColor: serverColor,
            title: "새 제목",
          });
        }
        return json({ ...board, signatureInkColor: serverColor });
      });
      const client = new QueryClient({
        defaultOptions: { queries: { retry: false } },
      });
      render(
        <QueryClientProvider client={client}>
          <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
            <ToastProvider>
              <Routes>
                <Route
                  element={<BoardEditorRoute />}
                  path="/boards/:boardId/edit"
                />
              </Routes>
            </ToastProvider>
          </MemoryRouter>
        </QueryClientProvider>,
      );
      await screen.findByRole("application", { name: "서명 보드 캔버스" });
      await setCsrfAfterReads(client);
      const saveStatus = screen.getByLabelText("자동 저장 상태");

      fireEvent.click(screen.getByRole("radio", { name: "흰색" }));
      await waitFor(() => expect(colorResponses).toEqual([500]));
      await waitFor(() => expect(saveStatus).toHaveTextContent("저장 실패"));
      setCsrfToken("csrf-test");
      fireEvent.keyDown(screen.getByRole("button", { name: "한별 이동" }), {
        key: "ArrowDown",
      });
      await waitFor(() => expect(layoutRequests).toBe(1));
      await waitFor(() =>
        expect(screen.getByRole("button", { name: "서명 시작" })).toBeEnabled(),
      );
      expect(saveStatus).toHaveTextContent("저장 실패");

      // When the second color request remains unresolved and title succeeds/refetches
      setCsrfToken("csrf-test");
      fireEvent.click(screen.getByRole("radio", { name: "흰색" }));
      await waitFor(() => expect(colorRequests).toBe(2));
      expect(colorResponses).toEqual([500]);
      expect(screen.getByRole("radio", { name: "흰색" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "서명 시작" })).toBeDisabled();
      fireEvent.change(screen.getByRole("textbox", { name: "보드 제목" }), {
        target: { value: "새 제목" },
      });
      fireEvent.click(screen.getByRole("button", { name: "제목 저장" }));
      await waitFor(() => expect(titleRequests).toBe(1));
      await waitFor(() => expect(client.isFetching()).toBe(0));

      // Then the unrelated success cannot claim every write is saved
      expect(colorResponses).toEqual([500]);
      expect(layoutRequests).toBe(1);
      expect(saveStatus).toHaveTextContent("저장 중");
      expect(saveStatus).not.toHaveTextContent("저장됨");
      expect(screen.getByRole("radio", { name: "흰색" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "서명 시작" })).toBeDisabled();

      // And only the relevant retry response owns the terminal projection
      releaseColorRetry();
      await waitFor(() =>
        expect(screen.getByRole("radio", { name: "흰색" })).toBeEnabled(),
      );
      await waitFor(() => expect(saveStatus).toHaveTextContent(expectedStatus));
      expect(
        screen.getByRole("radio", {
          name: expectedColor === "white" ? "흰색" : "검정",
        }),
      ).toBeChecked();
      expect(layoutRequests).toBe(1);
      expect(colorResponses).toEqual([500, retryStatus]);
    },
  );

  it("Given another tab opens the board When the color patch conflicts Then latest status and color lock the controls", async () => {
    // Given
    let boardReads = 0;
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(new Uint8Array([1]), {
          headers: { "Content-Type": "image/png" },
        });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (init?.method === "PATCH") return json({ code: "CONFLICT" }, 409);
      boardReads += 1;
      return boardReads === 1
        ? json(board)
        : json({ ...board, signatureInkColor: "white", status: "서명 진행" });
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    // When
    await setCsrfAfterReads(client);
    fireEvent.click(await screen.findByRole("radio", { name: "흰색" }));

    // Then
    expect(await screen.findByRole("button", { name: "마감" })).toBeVisible();
    expect(screen.getByRole("radio", { name: "흰색" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "흰색" })).toBeDisabled();
    expect(
      screen.getByText(
        "다른 화면에서 서명이 시작되었습니다. 최신 상태를 불러왔습니다.",
      ),
    ).toBeVisible();
  });

  it("Given a server color failure When white is selected Then the last server color returns with failed state", async () => {
    // Given
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(new Uint8Array([1]), {
          headers: { "Content-Type": "image/png" },
        });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (init?.method === "PATCH") return json({ code: "UNKNOWN" }, 500);
      return json(board);
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    // When
    await setCsrfAfterReads(client);
    fireEvent.click(await screen.findByRole("radio", { name: "흰색" }));

    // Then
    await waitFor(() =>
      expect(screen.getByRole("radio", { name: "검정" })).toBeChecked(),
    );
    expect(screen.getByLabelText("자동 저장 상태")).toHaveTextContent(
      "저장 실패",
    );
    expect(
      screen.getByText(
        "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
      ),
    ).toBeVisible();
  });

  it("Given a successful color patch and failed refetch When saving settles Then the patched color is not reverted", async () => {
    // Given
    let boardReads = 0;
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(new Uint8Array([1]), {
          headers: { "Content-Type": "image/png" },
        });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (init?.method === "PATCH")
        return json({ ...board, signatureInkColor: "white" });
      boardReads += 1;
      return boardReads === 1 ? json(board) : json({ code: "UNKNOWN" }, 500);
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    // When
    await setCsrfAfterReads(client);
    fireEvent.click(await screen.findByRole("radio", { name: "흰색" }));

    // Then
    await waitFor(() =>
      expect(screen.getByLabelText("자동 저장 상태")).toHaveTextContent(
        "저장됨",
      ),
    );
    expect(screen.getByRole("radio", { name: "흰색" })).toBeChecked();
    expect(screen.getByRole("button", { name: "서명 시작" })).toBeEnabled();
  });

  it("Given an in-flight color patch When the editor unmounts Then its completion does not write the board cache", async () => {
    // Given
    let releasePatch: () => void = () => undefined;
    const patchGate = new Promise<Response>((resolve) => {
      releasePatch = () =>
        resolve(json({ ...board, signatureInkColor: "white" }));
    });
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(new Uint8Array([1]), {
          headers: { "Content-Type": "image/png" },
        });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (init?.method === "PATCH") return patchGate;
      return json(board);
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const view = render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await setCsrfAfterReads(client);
    fireEvent.click(await screen.findByRole("radio", { name: "흰색" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "서명 시작" })).toBeDisabled(),
    );

    // When
    view.unmount();
    releasePatch();
    await act(async () => {
      await patchGate;
    });

    // Then
    expect(
      client.getQueryData<Board>(["admin", "boards", boardId]),
    ).toMatchObject({ signatureInkColor: "black" });
  });

  it("Given board A has an in-flight color patch When the route switches to board B Then B independently hydrates and unlocks", async () => {
    // Given
    const boardBId = "44444444-4444-4444-8444-444444444444";
    const boardB = { ...board, id: boardBId, title: "보드 B" };
    let releasePatch: () => void = () => undefined;
    const patchGate = new Promise<Response>((resolve) => {
      releasePatch = () =>
        resolve(json({ ...board, signatureInkColor: "white" }));
    });
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(new Uint8Array([1]), {
          headers: { "Content-Type": "image/png" },
        });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (path.endsWith(`/boards/${boardId}`) && init?.method === "PATCH")
        return patchGate;
      if (path.endsWith(`/boards/${boardBId}`)) return json(boardB);
      return json(board);
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Link to={`/boards/${boardBId}/edit`}>보드 B로 이동</Link>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await setCsrfAfterReads(client);
    fireEvent.click(await screen.findByRole("radio", { name: "흰색" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "서명 시작" })).toBeDisabled(),
    );

    // When
    fireEvent.click(screen.getByRole("link", { name: "보드 B로 이동" }));

    // Then
    expect(
      await screen.findByRole("heading", { name: "보드 B" }),
    ).toBeVisible();
    expect(screen.getByRole("radio", { name: "검정" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "검정" })).toBeEnabled();
    expect(screen.getByRole("radio", { name: "흰색" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "서명 시작" })).toBeEnabled();
    expect(screen.getByLabelText("자동 저장 상태")).toHaveTextContent(
      "변경 없음",
    );

    releasePatch();
    await act(async () => {
      await patchGate;
    });
    await waitFor(() =>
      expect(screen.getByRole("radio", { name: "검정" })).toBeChecked(),
    );
    expect(screen.getByRole("radio", { name: "검정" })).toBeEnabled();
    expect(screen.getByRole("radio", { name: "흰색" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "서명 시작" })).toBeEnabled();
    expect(
      client.getQueryData<Board>(["admin", "boards", boardBId]),
    ).toMatchObject({ signatureInkColor: "black" });
  });

  it("Given a malformed color response When white is selected Then strict parsing restores black", async () => {
    // Given
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(new Uint8Array([1]), {
          headers: { "Content-Type": "image/png" },
        });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (init?.method === "PATCH")
        return json({ ...board, signatureInkColor: "purple" });
      return json(board);
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    // When
    await setCsrfAfterReads(client);
    fireEvent.click(await screen.findByRole("radio", { name: "흰색" }));

    // Then
    await waitFor(() =>
      expect(screen.getByRole("radio", { name: "검정" })).toBeChecked(),
    );
    expect(screen.getByLabelText("자동 저장 상태")).toHaveTextContent(
      "저장 실패",
    );
    expect(screen.getByText("서버 응답을 확인할 수 없습니다.")).toBeVisible();
  });

  it("Given a draft without a background When color controls render Then black stays selected and white explains its lock", async () => {
    // Given
    renderEditor(() => new Response(null, { status: 204 }));

    // When
    const black = await screen.findByRole("radio", { name: "검정" });
    const white = screen.getByRole("radio", { name: "흰색" });

    // Then
    expect(black).toBeChecked();
    expect(black).toBeEnabled();
    expect(white).not.toBeChecked();
    expect(white).toBeDisabled();
    expect(screen.getByText("배경 이미지를 먼저 등록해 주세요")).toBeVisible();
  });

  it("Given a background still loading When the toolbar renders Then white remains unavailable until the read succeeds", async () => {
    // Given
    let releaseBackground: () => void = () => undefined;
    const backgroundGate = new Promise<Response>((resolve) => {
      releaseBackground = () => resolve(new Response(null, { status: 204 }));
    });
    renderEditor(() => backgroundGate);

    // When
    const white = await screen.findByRole("radio", { name: "흰색" });

    // Then
    expect(white).toBeDisabled();
    expect(screen.getByText("배경 이미지를 먼저 등록해 주세요")).toBeVisible();
    releaseBackground();
  });

  it("Given a loaded background When white is selected Then one strict patch locks start and keeps the successful color", async () => {
    // Given
    let releasePatch: () => void = () => undefined;
    const patchGate = new Promise<Response>((resolve) => {
      releasePatch = () =>
        resolve(json({ ...board, signatureInkColor: "white" }));
    });
    const requests: RequestInit[] = [];
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:background");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(new Uint8Array([1]), {
          headers: { "Content-Type": "image/png" },
        });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (init?.method === "PATCH") {
        requests.push(init);
        return patchGate;
      }
      return json(board);
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    const white = await screen.findByRole("radio", { name: "흰색" });
    const start = screen.getByRole("button", { name: "서명 시작" });

    // When
    await setCsrfAfterReads(client);
    fireEvent.click(white);
    fireEvent.click(white);

    // Then
    expect(white).toBeChecked();
    expect(start).toBeDisabled();
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]?.body).toBe(
      JSON.stringify({ signatureInkColor: "white" }),
    );
    releasePatch();
    await waitFor(() => expect(start).toBeEnabled());
    expect(white).toBeChecked();
  });

  it("Given an idle draft editor When signing starts Then the start control locks until the request finishes", async () => {
    // Given
    let releaseOpen: () => void = () => undefined;
    const openGate = new Promise<Response>((resolve) => {
      releaseOpen = () => resolve(json({ ...board, status: "서명 진행" }));
    });
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const path = input.toString();
      if (path.endsWith("/background"))
        return new Response(null, { status: 204 });
      if (path.endsWith("/roster")) return json(roster);
      if (path.endsWith("/share"))
        return json({ shareToken: "safe-share", version: 1 });
      if (path.endsWith("/open") && init?.method === "POST") return openGate;
      return json(board);
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
          <ToastProvider>
            <Routes>
              <Route
                element={<BoardEditorRoute />}
                path="/boards/:boardId/edit"
              />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    const start = await screen.findByRole("button", { name: "서명 시작" });
    expect(start).toBeEnabled();

    // When
    fireEvent.click(start);

    // Then
    expect(start).toBeDisabled();
    releaseOpen();
    await waitFor(() => expect(start).toBeEnabled());
  });

  it.each(["signature-draft", "signature-draft-cleared"])(
    "does not reload editor resources for %s",
    async (eventType) => {
      // Given
      const listeners = new Map<string, EventListener>();
      vi.stubGlobal(
        "EventSource",
        class {
          onerror: (() => void) | null = null;
          onopen: (() => void) | null = null;
          addEventListener(type: string, listener: EventListener) {
            listeners.set(type, listener);
          }
          close() {}
        },
      );
      renderEditor(() => new Response(null, { status: 204 }), "서명 진행");
      await screen.findByRole("application", { name: "서명 보드 캔버스" });
      const fetch = vi.mocked(globalThis.fetch);
      fetch.mockClear();

      // When
      for (let index = 0; index < 3; index += 1) {
        await act(async () => {
          listeners.get(eventType)?.(new Event(eventType));
        });
      }

      // Then
      expect(fetch).not.toHaveBeenCalled();
      expect(
        screen.getByRole("application", { name: "서명 보드 캔버스" }),
      ).toBeVisible();
    },
  );

  it("Given the editor When it mounts Then owner realtime refetch connects", async () => {
    // Given
    const connections: string[] = [];
    vi.stubGlobal(
      "EventSource",
      class {
        onerror: (() => void) | null = null;
        onopen: (() => void) | null = null;
        constructor(url: string) {
          connections.push(url);
        }
        addEventListener() {}
        close() {}
      },
    );

    // When
    renderEditor(() => new Response(null, { status: 204 }));
    await screen.findByRole("application", { name: "서명 보드 캔버스" });

    // Then
    expect(connections).toContain(`/api/v1/admin/boards/${boardId}/events`);
  });

  it("Given current background read failure When retry succeeds Then a safe blocking error recovers", async () => {
    let reads = 0;
    renderEditor(() =>
      reads++ === 0
        ? json(
            {
              code: "OBJECT_KEY_MISSING",
              objectKey: "private/owner/board.png",
            },
            404,
          )
        : new Response(null, { status: 204 }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "배경을 불러오지 못했습니다",
    );
    expect(screen.getByRole("alert")).not.toHaveTextContent(
      "OBJECT_KEY_MISSING",
    );
    expect(screen.getByRole("alert")).not.toHaveTextContent(
      "private/owner/board.png",
    );
    expect(screen.getByLabelText("PNG 또는 JPEG 파일 선택")).toBeDisabled();
    expect(screen.getByRole("radio", { name: "흰색" })).toBeDisabled();
    expect(screen.getByText("배경 이미지를 먼저 등록해 주세요")).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: "배경 다시 불러오기" }));

    expect(
      await screen.findByRole("application", { name: "서명 보드 캔버스" }),
    ).toBeVisible();
    expect(screen.getByLabelText("PNG 또는 JPEG 파일 선택")).toBeEnabled();
  });

  it("Given the editor When controls render Then shared board button primitives are reused", async () => {
    renderEditor(() => new Response(null, { status: 204 }));

    await screen.findByRole("application", { name: "서명 보드 캔버스" });
    await waitFor(() =>
      expect(screen.getAllByRole("button").length).toBeGreaterThan(5),
    );
    for (const button of screen.getAllByRole("button"))
      expect(button).toHaveClass("board-button");
  });

  it("Given active signing When editor controls render Then destructive extensions stay hidden", async () => {
    renderEditor(
      () => new Response(null, { status: 204 }),
      "서명 진행",
      <button className="board-button board-button--destructive" type="button">
        보드 영구 삭제
      </button>,
    );

    await screen.findByRole("application", { name: "서명 보드 캔버스" });
    expect(
      screen.queryByRole("button", { name: "보드 영구 삭제" }),
    ).not.toBeInTheDocument();
  });

  it("Given a display replacement failure When an administrator confirms replacement Then one force request runs and its toast is visible", async () => {
    // Given
    let forceRequests = 0;
    document.cookie = "XSRF-TOKEN=csrf-test; Path=/";
    renderEditor(
      () => new Response(null, { status: 204 }),
      board.status,
      undefined,
      () => {
        forceRequests += 1;
        return json({ code: "SERVICE_UNAVAILABLE" }, 503);
      },
    );
    await screen.findByRole("application", { name: "서명 보드 캔버스" });

    // When
    fireEvent.click(screen.getByRole("button", { name: "행사장 화면 교체" }));
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(forceRequests).toBe(0);
    fireEvent.click(screen.getByRole("button", { name: "행사장 화면 교체" }));
    fireEvent.click(screen.getByRole("button", { name: "화면 교체" }));

    // Then
    await waitFor(() => expect(forceRequests).toBe(1));
    expect(
      await screen.findByText("서비스에 연결할 수 없습니다."),
    ).toBeVisible();
  });
});
