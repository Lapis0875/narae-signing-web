import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { FullViewRoute } from "../../../routes/FullViewRoute.tsx";
import { deferred } from "../../signing/pad/signaturePadTestSupport.ts";

const boardId = "00000000-0000-4000-8000-000000000001";
const slotId = "00000000-0000-4000-8000-000000000002";

class ControlledEventSource extends EventTarget {
  static instances: ControlledEventSource[] = [];
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;

  constructor(readonly url: string) {
    super();
    ControlledEventSource.instances.push(this);
  }

  close() {
    this.closed = true;
  }
  emit(type: string) {
    this.dispatchEvent(new MessageEvent(type, { data: "{}" }));
  }
}

function snapshot(signed: boolean): Response {
  return Response.json({
    backgroundPresent: false,
    boardId,
    canvasHeight: 600,
    canvasWidth: 800,
    signatureInkColor: "black",
    slots: [
      {
        draftEpoch: 1,
        draftSignature: signed
          ? { strokes: [{ points: [{ x: 100, y: 200 }] }], version: 1 }
          : null,
        height: 0.2,
        id: slotId,
        revision: signed ? 1 : 0,
        signature: null,
        width: 0.3,
        x: 0.1,
        y: 0.2,
      },
    ],
  });
}

async function display() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/boards/${boardId}/full`]}>
        <Routes>
          <Route element={<FullViewRoute />} path="/boards/:boardId/full" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  await screen.findByTestId("full-view-canvas");
  await screen.findByRole("img", { name: "서명 보드 전체보기" });
  const source = ControlledEventSource.instances[0];
  if (source === undefined) throw new Error("Expected admin EventSource");
  await act(async () => {
    source.onopen?.();
  });
  await waitFor(() => expect(client.isFetching()).toBe(0));
  return { client, source };
}

beforeEach(() => {
  vi.stubGlobal("EventSource", ControlledEventSource);
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      disconnect() {}
    },
  );
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  ControlledEventSource.instances = [];
});

it("renders incoming draft while a background update response remains pending", async () => {
  // Given
  const backgroundResponse = deferred<Response>();
  let backgroundRequests = 0;
  let signed = false;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    if (!input.toString().endsWith("/background")) return snapshot(signed);
    backgroundRequests += 1;
    return backgroundRequests <= 2
      ? new Response(null, { status: 204 })
      : backgroundResponse.promise;
  });
  const { source } = await display();
  await act(async () => {
    source.emit("background-updated");
  });
  expect(backgroundRequests).toBe(3);

  try {
    // When
    signed = true;
    await act(async () => {
      source.emit("signature-draft");
    });

    // Then
    expect(await screen.findByTestId("submitted-signature")).toBeVisible();
    expect(backgroundRequests).toBe(3);
  } finally {
    await act(async () => {
      backgroundResponse.resolve(new Response(null, { status: 204 }));
    });
  }
});

it.each([
  "signature-draft",
  "signature-draft-cleared",
  "signature-submitted",
  "signature-reset",
])(
  "refreshes visible snapshot without downloading unchanged background for %s",
  async (eventType) => {
    // Given
    let signed = false;
    let backgroundRequests = 0;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (!input.toString().endsWith("/background")) return snapshot(signed);
      backgroundRequests += 1;
      return new Response(null, { status: 204 });
    });
    const { source } = await display();
    const initialBackgroundRequests = backgroundRequests;

    // When
    signed = true;
    await act(async () => {
      source.emit(eventType);
    });

    // Then
    expect(await screen.findByTestId("submitted-signature")).toBeVisible();
    expect(backgroundRequests).toBe(initialBackgroundRequests);
  },
);

it("keeps one final snapshot follow-up for a burst while the current snapshot is pending", async () => {
  // Given
  const pendingSnapshot = deferred<Response>();
  let snapshotRequests = 0;
  let backgroundRequests = 0;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    if (input.toString().endsWith("/background")) {
      backgroundRequests += 1;
      return new Response(null, { status: 204 });
    }
    snapshotRequests += 1;
    return snapshotRequests === 3
      ? pendingSnapshot.promise
      : snapshot(snapshotRequests > 3);
  });
  const { source } = await display();
  await act(async () => {
    source.emit("signature-draft");
  });

  // When
  await act(async () => {
    for (let index = 0; index < 20; index += 1) source.emit("signature-draft");
  });
  expect(snapshotRequests).toBe(3);
  await act(async () => {
    pendingSnapshot.resolve(snapshot(false));
  });

  // Then
  expect(await screen.findByTestId("submitted-signature")).toBeVisible();
  expect(snapshotRequests).toBe(4);
  expect(backgroundRequests).toBe(2);
});

it("reloads background on a background event and on reconnect", async () => {
  // Given
  let backgroundRequests = 0;
  let snapshotRequests = 0;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    if (input.toString().endsWith("/background")) {
      backgroundRequests += 1;
      return new Response(null, { status: 204 });
    }
    snapshotRequests += 1;
    return snapshot(false);
  });
  const { source } = await display();

  // When
  await act(async () => {
    source.emit("background-updated");
  });
  expect(backgroundRequests).toBe(3);
  vi.useFakeTimers();
  await act(async () => {
    source.onerror?.();
    await vi.advanceTimersByTimeAsync(250);
  });
  const reconnected = ControlledEventSource.instances[1];
  if (reconnected === undefined)
    throw new Error("Expected reconnected EventSource");
  await act(async () => {
    reconnected.onopen?.();
    await vi.advanceTimersByTimeAsync(0);
  });

  // Then
  expect(source.closed).toBe(true);
  expect(backgroundRequests).toBe(4);
  expect(snapshotRequests).toBe(4);
});
