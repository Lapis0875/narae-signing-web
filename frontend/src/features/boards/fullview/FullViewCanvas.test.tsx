import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FullViewCanvas } from "./FullViewCanvas.tsx";
import {
  calculateDisplayLineWidth,
  signatureInkColorCss,
} from "./SignatureGeometry.tsx";

describe("full view canvas", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("renders configured slots without roster identity or editor controls", () => {
    // Given
    const snapshot = {
      backgroundPresent: false,
      boardId: "00000000-0000-4000-8000-000000000001",
      canvasHeight: 600,
      canvasWidth: 800,
      signatureInkColor: "black" as const,
      slots: [
        {
          draftEpoch: 0,
          draftSignature: null,
          height: 0.2,
          id: "00000000-0000-4000-8000-000000000002",
          revision: 0,
          signature: null,
          width: 0.3,
          x: 0.1,
          y: 0.2,
        },
      ],
    };

    // When
    const { container } = render(
      <FullViewCanvas backgroundUrl={null} snapshot={snapshot} />,
    );

    // Then
    expect(
      container.querySelector("[data-background]"),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(container).not.toHaveTextContent("name");
    expect(container.querySelector(".slot-overlay")).not.toBeInTheDocument();
  });

  it("renders the current unsubmitted draft instead of leaving its slot blank", () => {
    // Given
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe() {}
        disconnect() {}
      },
    );
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
    const snapshot = {
      backgroundPresent: false,
      boardId: "00000000-0000-4000-8000-000000000001",
      canvasHeight: 600,
      canvasWidth: 800,
      signatureInkColor: "black" as const,
      slots: [
        {
          draftEpoch: 1,
          draftSignature: {
            strokes: [{ points: [{ x: 100, y: 200 }] }],
            version: 1 as const,
          },
          height: 0.2,
          id: "00000000-0000-4000-8000-000000000002",
          revision: 1,
          signature: null,
          width: 0.3,
          x: 0.1,
          y: 0.2,
        },
      ],
    };

    // When
    render(<FullViewCanvas backgroundUrl={null} snapshot={snapshot} />);

    // Then
    expect(screen.getByTestId("submitted-signature")).toBeInTheDocument();
  });

  it("preserves exact signature geometry inside the configured slot", () => {
    // Given
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe() {}
        disconnect() {}
      },
    );
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
    const signature = {
      strokes: [
        {
          points: [
            { x: 125_000, y: 250_000 },
            { x: 875_000, y: 750_000 },
          ],
        },
      ],
      version: 1 as const,
    };
    const snapshot = {
      backgroundPresent: false,
      boardId: "00000000-0000-4000-8000-000000000001",
      canvasHeight: 600,
      canvasWidth: 800,
      signatureInkColor: "black" as const,
      slots: [
        {
          draftEpoch: 0,
          draftSignature: null,
          height: 0.4,
          id: "00000000-0000-4000-8000-000000000002",
          revision: 0,
          signature,
          width: 0.6,
          x: 0.125,
          y: 0.25,
        },
      ],
    };

    // When
    const { container } = render(
      <FullViewCanvas backgroundUrl={null} snapshot={snapshot} />,
    );

    // Then
    expect(
      container.querySelector<HTMLElement>("[data-slot-id]")?.style.cssText,
    ).toContain("left: 12.5%");
    expect(
      container.querySelector<HTMLElement>("[data-slot-id]")?.style.cssText,
    ).toContain("top: 25%");
    expect(
      container.querySelector<HTMLElement>("[data-slot-id]")?.style.cssText,
    ).toContain("width: 60%");
    expect(
      container.querySelector<HTMLElement>("[data-slot-id]")?.style.cssText,
    ).toContain("height: 40%");
    expect(screen.getByTestId("submitted-signature")).toBeInTheDocument();
  });

  it("uses one aspect-ratio frame for the background and normalized slot geometry", () => {
    // Given
    const snapshot = {
      backgroundPresent: true,
      boardId: "00000000-0000-4000-8000-000000000001",
      canvasHeight: 1_080,
      canvasWidth: 1_920,
      signatureInkColor: "white" as const,
      slots: [
        {
          draftEpoch: 0,
          draftSignature: null,
          height: 0.18,
          id: "00000000-0000-4000-8000-000000000002",
          revision: 0,
          signature: null,
          width: 0.24,
          x: 0,
          y: 0,
        },
      ],
    };

    // When
    const { container } = render(
      <FullViewCanvas backgroundUrl="blob:background" snapshot={snapshot} />,
    );

    // Then
    const board = screen.getByTestId("full-view-canvas");
    const background = container.querySelector("img");
    const slot = container.querySelector("[data-slot-id]");
    expect(background).not.toBeNull();
    expect(slot).not.toBeNull();
    expect(background?.parentElement).toBe(slot?.parentElement);
    expect(background?.parentElement).not.toBe(board);
    expect(background?.parentElement).toHaveStyle({
      aspectRatio: "1920 / 1080",
    });
  });

  it("redraws identical signature geometry when only the board ink color changes", () => {
    // Given
    const redrawCallbacks: ResizeObserverCallback[] = [];
    vi.stubGlobal(
      "ResizeObserver",
      class {
        constructor(callback: ResizeObserverCallback) {
          redrawCallbacks.push(callback);
        }
        observe() {}
        disconnect() {}
      },
    );
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
    const signature = {
      strokes: [
        {
          points: [
            { x: 125_000, y: 250_000 },
            { x: 875_000, y: 750_000 },
          ],
        },
      ],
      version: 1 as const,
    };
    const snapshot = {
      backgroundPresent: false,
      boardId: "00000000-0000-4000-8000-000000000001",
      canvasHeight: 600,
      canvasWidth: 800,
      signatureInkColor: "black" as const,
      slots: [
        {
          draftEpoch: 1,
          draftSignature: signature,
          height: 1,
          id: "00000000-0000-4000-8000-000000000002",
          revision: 2,
          signature: null,
          width: 1,
          x: 0,
          y: 0,
        },
      ],
    };
    const { rerender } = render(
      <FullViewCanvas backgroundUrl={null} snapshot={snapshot} />,
    );

    // When
    rerender(
      <FullViewCanvas
        backgroundUrl={null}
        snapshot={{ ...snapshot, signatureInkColor: "white" }}
      />,
    );

    // Then
    expect(redrawCallbacks).toHaveLength(2);
    expect(signatureInkColorCss("black")).toBe("#000000");
    expect(signatureInkColorCss("white")).toBe("#FFFFFF");
  });

  it("keeps a two-device-pixel opaque interior without widening high-density previews", () => {
    // Given / When / Then
    expect(calculateDisplayLineWidth(236, 100, 1)).toBe(2);
    expect(calculateDisplayLineWidth(236, 100, 2)).toBe(1);
    expect(calculateDisplayLineWidth(480, 200, 1)).toBe(2);
  });
});
