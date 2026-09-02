import "@testing-library/jest-dom/vitest"
import { cleanup, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"
import { FullViewCanvas } from "./FullViewCanvas.tsx"

describe("full view canvas", () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

  it("renders configured slots without roster identity or editor controls", () => {
    // Given
    const snapshot = {
      backgroundPresent: false,
      boardId: "00000000-0000-4000-8000-000000000001",
      canvasHeight: 600,
      canvasWidth: 800,
      slots: [{
        background: "white" as const,
        draftSignature: null,
        height: 0.2,
        id: "00000000-0000-4000-8000-000000000002",
        signature: null,
        width: 0.3,
        x: 0.1,
        y: 0.2,
      }],
    }

    // When
    const { container } = render(<FullViewCanvas backgroundUrl={null} snapshot={snapshot} />)

    // Then
    expect(container.querySelector('[data-background="white"]')).toBeInTheDocument()
    expect(screen.queryByRole("button")).not.toBeInTheDocument()
    expect(container).not.toHaveTextContent("name")
    expect(container.querySelector(".slot-overlay")).not.toBeInTheDocument()
  })

  it("renders the current unsubmitted draft instead of leaving its slot blank", () => {
    // Given
    vi.stubGlobal("ResizeObserver", class {
      observe() {}
      disconnect() {}
    })
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null)
    const snapshot = {
      backgroundPresent: false,
      boardId: "00000000-0000-4000-8000-000000000001",
      canvasHeight: 600,
      canvasWidth: 800,
      slots: [{
        background: "transparent" as const,
        draftSignature: { strokes: [{ points: [{ x: 100, y: 200 }] }], version: 1 as const },
        height: 0.2,
        id: "00000000-0000-4000-8000-000000000002",
        signature: null,
        width: 0.3,
        x: 0.1,
        y: 0.2,
      }],
    }

    // When
    render(<FullViewCanvas backgroundUrl={null} snapshot={snapshot} />)

    // Then
    expect(screen.getByTestId("submitted-signature")).toBeInTheDocument()
  })
})
