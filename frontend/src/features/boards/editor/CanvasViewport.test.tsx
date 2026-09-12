import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CanvasViewport } from "./CanvasViewport.tsx";
import "./editor.css";

const entries = [
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
      height: 0.2,
      id: "22222222-2222-4222-8222-222222222222",
      placementStatus: "PLACED",
      revision: 1,
      width: 0.2,
      x: 0.2,
      y: 0,
    },
    submitted: false,
  },
] as const;

describe("CanvasViewport", () => {
  afterEach(cleanup);

  it("Given a predicted overlap When keyboard movement finishes Then the server save is still requested", () => {
    const onSave = vi.fn();
    render(
      <CanvasViewport
        backgroundUrl={null}
        canvasHeight={1080}
        canvasWidth={1920}
        entries={entries}
        onSave={onSave}
        onUnplace={vi.fn()}
      />,
    );

    fireEvent.keyDown(screen.getByRole("button", { name: "한별 이동" }), {
      key: "ArrowRight",
    });

    expect(onSave).toHaveBeenCalledOnce();
    const collidingSlot = screen.getByTestId(
      "slot-21111111-1111-4111-8111-111111111111",
    );
    expect(collidingSlot).toHaveClass("slot-overlay--collision");
    expect(getComputedStyle(collidingSlot).backgroundColor).toBe(
      "rgba(0, 0, 0, 0)",
    );
  });

  it("Given a placed slot When rendered Then it has no background control or opaque fill", () => {
    render(
      <CanvasViewport
        backgroundUrl={null}
        canvasHeight={1080}
        canvasWidth={1920}
        entries={entries}
        onSave={vi.fn()}
        onUnplace={vi.fn()}
      />,
    );

    expect(
      screen.queryByRole("button", { name: "한별 칸 배경 변경" }),
    ).not.toBeInTheDocument();
    expect(
      getComputedStyle(
        screen.getByTestId("slot-21111111-1111-4111-8111-111111111111"),
      ).backgroundColor,
    ).toBe("rgba(0, 0, 0, 0)");
  });

  it("Given an active gesture When the canvas unmounts Then a late pointer release cannot save stale geometry", () => {
    const onSave = vi.fn();
    const { unmount } = render(
      <CanvasViewport
        backgroundUrl={null}
        canvasHeight={1080}
        canvasWidth={1920}
        entries={entries}
        onSave={onSave}
        onUnplace={vi.fn()}
      />,
    );
    const moveButton = screen.getByRole("button", { name: "한별 이동" });
    Object.defineProperty(moveButton, "setPointerCapture", {
      configurable: true,
      value: vi.fn(),
    });
    fireEvent.pointerDown(moveButton, {
      clientX: 10,
      clientY: 10,
      pointerId: 1,
    });

    unmount();
    fireEvent.pointerUp(document, { clientX: 20, clientY: 20, pointerId: 1 });

    expect(onSave).not.toHaveBeenCalled();
  });
});
