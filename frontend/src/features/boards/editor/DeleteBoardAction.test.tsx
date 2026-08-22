import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setCsrfToken } from "../../../api/client.ts";
import { DeleteBoardAction } from "./DeleteBoardAction.tsx";

const boardId = "11111111-1111-4111-8111-111111111111";

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
    configurable: true,
    value: vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute("open", "");
    }),
  });
  Object.defineProperty(HTMLDialogElement.prototype, "close", {
    configurable: true,
    value: vi.fn(function (this: HTMLDialogElement) {
      this.removeAttribute("open");
    }),
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  Reflect.deleteProperty(HTMLDialogElement.prototype, "close");
  Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal");
});

describe("DeleteBoardAction", () => {
  it("Given no confirmation When time passes Then deletion remains explicit-only", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    renderAction();

    fireEvent.click(screen.getByRole("button", { name: "보드 영구 삭제" }));
    expect(screen.getByRole("dialog")).toBeTruthy();
    await new Promise((resolve) => window.setTimeout(resolve, 1));
    expect(fetchMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("Given confirmation When confirmed Then it sends one owner-authenticated delete request", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    setCsrfToken("csrf-test");
    renderAction();

    fireEvent.click(screen.getByRole("button", { name: "보드 영구 삭제" }));
    fireEvent.click(screen.getByRole("button", { name: "영구 삭제" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/admin/boards/${boardId}`,
      expect.objectContaining({
        body: JSON.stringify({ confirmed: true }),
        method: "DELETE",
      }),
    );
  });
});

function renderAction() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/boards/${boardId}/edit`]}>
        <Routes>
          <Route path="/boards/:boardId/edit" element={<DeleteBoardAction />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
