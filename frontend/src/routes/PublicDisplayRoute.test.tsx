import "@testing-library/jest-dom/vitest";
import { act, cleanup, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  publicLinkResponse,
  renderDisplay,
  resetPublicDisplayTest,
  shareToken,
  snapshotResponse,
  TestEventSource,
} from "./PublicDisplayRoute.test-support.tsx";

afterEach(resetPublicDisplayTest);

describe("public display lease ownership and realtime", () => {
  it("requests no board content before claim succeeds", async () => {
    const requests: string[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation((input) => {
      const url = input.toString();
      requests.push(url);
      if (url === `/api/v1/public/links/${shareToken}`)
        return Promise.resolve(publicLinkResponse());
      return new Promise(() => undefined);
    });

    renderDisplay();

    await waitFor(() =>
      expect(requests).toEqual([
        `/api/v1/public/links/${shareToken}`,
        `/api/v1/public/links/${shareToken}/display/claim`,
      ]),
    );
    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
    expect(screen.queryByTestId("full-view-canvas")).not.toBeInTheDocument();
  });

  it("shows only the exact denial contract when another display owns the board", async () => {
    const requests: string[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString();
      requests.push(url);
      if (url === `/api/v1/public/links/${shareToken}`)
        return publicLinkResponse();
      return Response.json(
        {
          code: "DISPLAY_ALREADY_CONNECTED",
          message: "다른 화면에서 이미 보드를 표시하고 있습니다.",
        },
        { status: 409 },
      );
    });

    renderDisplay();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "다른 화면에서 이미 보드를 표시하고 있습니다.",
    );
    expect(requests).toEqual([
      `/api/v1/public/links/${shareToken}`,
      `/api/v1/public/links/${shareToken}/display/claim`,
    ]);
    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
    expect(screen.queryByTestId("full-view-canvas")).not.toBeInTheDocument();
  });

  it("treats a malformed conflict as an ordinary initial error", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) =>
      input.toString() === `/api/v1/public/links/${shareToken}`
        ? publicLinkResponse()
        : Response.json(
            { code: "DISPLAY_ALREADY_CONNECTED", message: "wrong message" },
            { status: 409 },
          ),
    );

    renderDisplay();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "행사장 화면을 불러오지 못했습니다.",
    );
    expect(
      screen.queryByText("다른 화면에서 이미 보드를 표시하고 있습니다."),
    ).not.toBeInTheDocument();
  });

  it("renders a contiguous draft without refetching and recovers a revision gap", async () => {
    vi.stubGlobal("EventSource", TestEventSource);
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe() {}
        disconnect() {}
      },
    );
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
    let snapshotRequests = 0;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString();
      if (url === `/api/v1/public/links/${shareToken}`)
        return publicLinkResponse();
      if (url.endsWith("/claim")) return new Response(null, { status: 204 });
      if (url.endsWith("/title"))
        return Response.json({ title: "행사장 공개 화면" });
      if (url.endsWith("/background"))
        return new Response(null, { status: 204 });
      snapshotRequests += 1;
      return Response.json({
        backgroundPresent: false,
        boardId: "00000000-0000-4000-8000-000000000011",
        canvasHeight: 600,
        canvasWidth: 800,
        signatureInkColor: "black",
        slots: [
          {
            draftEpoch: 1,
            draftSignature: { strokes: [], version: 1 },
            height: 0.2,
            id: "00000000-0000-4000-8000-000000000012",
            revision: 0,
            signature: null,
            width: 0.3,
            x: 0.1,
            y: 0.2,
          },
        ],
      });
    });
    renderDisplay();
    await screen.findByTestId("full-view-canvas");
    await waitFor(() => expect(snapshotRequests).toBeGreaterThanOrEqual(1));
    const beforeDraft = snapshotRequests;

    await act(async () => {
      TestEventSource.current?.emit(
        "signature-draft",
        JSON.stringify({
          draftEpoch: 1,
          operation: "begin",
          points: [{ x: 100, y: 200 }],
          revision: 1,
          slotId: "00000000-0000-4000-8000-000000000012",
          strokeIndex: 0,
        }),
      );
    });

    expect(screen.getByTestId("submitted-signature")).toBeVisible();
    expect(snapshotRequests).toBe(beforeDraft);

    await act(async () => {
      TestEventSource.current?.emit(
        "signature-draft",
        JSON.stringify({
          draftEpoch: 1,
          operation: "append",
          points: [{ x: 300, y: 400 }],
          revision: 3,
          slotId: "00000000-0000-4000-8000-000000000012",
          strokeIndex: 0,
        }),
      );
    });

    await waitFor(() => expect(snapshotRequests).toBeGreaterThan(beforeDraft));
  });

  it("redraws board-updated ink color without deleting locally newer draft geometry", async () => {
    // Given
    vi.stubGlobal("EventSource", TestEventSource);
    let resizeObservers = 0;
    vi.stubGlobal(
      "ResizeObserver",
      class {
        constructor(_callback: ResizeObserverCallback) {
          resizeObservers += 1;
        }
        observe() {}
        disconnect() {}
      },
    );
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
    let inkColor: "black" | "white" = "black";
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString();
      if (url === `/api/v1/public/links/${shareToken}`)
        return publicLinkResponse();
      if (url.endsWith("/claim")) return new Response(null, { status: 204 });
      if (url.endsWith("/title"))
        return Response.json({ title: "행사장 공개 화면" });
      if (url.endsWith("/background"))
        return new Response(null, { status: 204 });
      return Response.json({
        backgroundPresent: false,
        boardId: "00000000-0000-4000-8000-000000000011",
        canvasHeight: 600,
        canvasWidth: 800,
        signatureInkColor: inkColor,
        slots: [
          {
            draftEpoch: 1,
            draftSignature: {
              strokes: [{ points: [{ x: 100_000, y: 200_000 }] }],
              version: 1,
            },
            height: 1,
            id: "00000000-0000-4000-8000-000000000012",
            revision: 1,
            signature: null,
            width: 1,
            x: 0,
            y: 0,
          },
        ],
      });
    });
    renderDisplay();
    await screen.findByTestId("submitted-signature");
    await act(async () => {
      TestEventSource.current?.emit(
        "signature-draft",
        JSON.stringify({
          draftEpoch: 1,
          operation: "append",
          points: [{ x: 900_000, y: 800_000 }],
          revision: 2,
          slotId: "00000000-0000-4000-8000-000000000012",
          strokeIndex: 0,
        }),
      );
    });
    const observersAfterNewerGeometry = resizeObservers;

    // When
    inkColor = "white";
    await act(async () => {
      TestEventSource.current?.emit("board-updated");
    });

    // Then
    await waitFor(() =>
      expect(resizeObservers).toBe(observersAfterNewerGeometry + 1),
    );
    expect(screen.getByTestId("submitted-signature")).toBeVisible();
  });

  it("adopts the locked snapshot color on reconnect while retaining newer draft geometry", async () => {
    // Given
    vi.stubGlobal("EventSource", TestEventSource);
    let resizeObservers = 0;
    vi.stubGlobal(
      "ResizeObserver",
      class {
        constructor(_callback: ResizeObserverCallback) {
          resizeObservers += 1;
        }
        observe() {}
        disconnect() {}
      },
    );
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
    let inkColor: "black" | "white" = "white";
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString();
      if (url === `/api/v1/public/links/${shareToken}`)
        return publicLinkResponse();
      if (url.endsWith("/claim")) return new Response(null, { status: 204 });
      if (url.endsWith("/title"))
        return Response.json({ title: "행사장 공개 화면" });
      if (url.endsWith("/background"))
        return new Response(null, { status: 204 });
      return Response.json({
        backgroundPresent: false,
        boardId: "00000000-0000-4000-8000-000000000011",
        canvasHeight: 600,
        canvasWidth: 800,
        signatureInkColor: inkColor,
        slots: [
          {
            draftEpoch: 1,
            draftSignature: {
              strokes: [{ points: [{ x: 100_000, y: 200_000 }] }],
              version: 1,
            },
            height: 1,
            id: "00000000-0000-4000-8000-000000000012",
            revision: 1,
            signature: null,
            width: 1,
            x: 0,
            y: 0,
          },
        ],
      });
    });
    renderDisplay();
    await screen.findByTestId("submitted-signature");
    await act(async () => {
      TestEventSource.current?.emit(
        "signature-draft",
        JSON.stringify({
          draftEpoch: 1,
          operation: "append",
          points: [{ x: 900_000, y: 800_000 }],
          revision: 2,
          slotId: "00000000-0000-4000-8000-000000000012",
          strokeIndex: 0,
        }),
      );
    });
    const observersAfterNewerGeometry = resizeObservers;
    const disconnected = TestEventSource.current;
    if (disconnected === null) throw new Error("Expected initial EventSource");

    // When
    inkColor = "black";
    act(() => {
      disconnected.onerror?.(new Event("error"));
    });

    // Then
    await waitFor(() => expect(TestEventSource.current).not.toBe(disconnected));
    await waitFor(() =>
      expect(resizeObservers).toBe(observersAfterNewerGeometry + 1),
    );
    expect(disconnected.closed).toBe(true);
    expect(screen.getByTestId("submitted-signature")).toBeVisible();
  });

  it("cancels a scheduled reconnect and closes its EventSource on unmount", async () => {
    // Given
    vi.stubGlobal("EventSource", TestEventSource);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString();
      if (url === `/api/v1/public/links/${shareToken}`)
        return publicLinkResponse();
      if (url.endsWith("/claim")) return new Response(null, { status: 204 });
      if (url.endsWith("/title"))
        return Response.json({ title: "행사장 공개 화면" });
      if (url.endsWith("/background"))
        return new Response(null, { status: 204 });
      return snapshotResponse();
    });
    renderDisplay();
    await screen.findByTestId("full-view-canvas");
    const disconnected = TestEventSource.current;
    if (disconnected === null) throw new Error("Expected initial EventSource");
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    act(() => {
      disconnected.onerror?.(new Event("error"));
    });

    // When
    cleanup();
    await vi.advanceTimersByTimeAsync(250);

    // Then
    expect(disconnected.closed).toBe(true);
    expect(TestEventSource.current).toBe(disconnected);
  });

  it("replaces the canvas only after the definite replacement event", async () => {
    vi.stubGlobal("EventSource", TestEventSource);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString();
      if (url === `/api/v1/public/links/${shareToken}`)
        return publicLinkResponse();
      if (url.endsWith("/claim")) return new Response(null, { status: 204 });
      if (url.endsWith("/title"))
        return Response.json({ title: "행사장 공개 화면" });
      if (url.endsWith("/background"))
        return new Response(null, { status: 204 });
      return snapshotResponse();
    });
    renderDisplay();
    await screen.findByTestId("full-view-canvas");

    act(() => {
      TestEventSource.current?.emit("display-replaced");
    });

    expect(screen.getByRole("alert")).toHaveTextContent(
      "이 화면의 표시 연결이 다른 화면으로 전환되었습니다.",
    );
    expect(screen.queryByTestId("full-view-canvas")).not.toBeInTheDocument();
  });

  it("replaces the canvas when a heartbeat receives the exact denial", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    vi.stubGlobal("EventSource", TestEventSource);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = input.toString();
      if (url === `/api/v1/public/links/${shareToken}`)
        return publicLinkResponse();
      if (url.endsWith("/claim")) return new Response(null, { status: 204 });
      if (url.endsWith("/heartbeat"))
        return Response.json(
          {
            code: "DISPLAY_ALREADY_CONNECTED",
            message: "다른 화면에서 이미 보드를 표시하고 있습니다.",
          },
          { status: 409 },
        );
      if (url.endsWith("/title"))
        return Response.json({ title: "행사장 공개 화면" });
      if (url.endsWith("/background"))
        return new Response(null, { status: 204 });
      return snapshotResponse();
    });
    renderDisplay();
    await screen.findByTestId("full-view-canvas");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });

    expect(screen.getByRole("alert")).toHaveTextContent(
      "다른 화면에서 이미 보드를 표시하고 있습니다.",
    );
    expect(screen.queryByTestId("full-view-canvas")).not.toBeInTheDocument();
  });
});
