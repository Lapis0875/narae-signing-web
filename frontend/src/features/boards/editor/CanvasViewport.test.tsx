import "@testing-library/jest-dom/vitest"
import { fireEvent, render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"
import { CanvasViewport } from "./CanvasViewport.tsx"

const entries = [
  {
    id: "31111111-1111-4111-8111-111111111111",
    identity: { job: "담당", name: "한별", organization: "나래" },
    slot: { backgroundColor: "transparent", height: 0.2, id: "21111111-1111-4111-8111-111111111111", placementStatus: "PLACED", revision: 1, width: 0.2, x: 0, y: 0 },
    submitted: false,
  },
  {
    id: "32222222-2222-4222-8222-222222222222",
    identity: { job: "담당", name: "누리", organization: "나래" },
    slot: { backgroundColor: "transparent", height: 0.2, id: "22222222-2222-4222-8222-222222222222", placementStatus: "PLACED", revision: 1, width: 0.2, x: 0.2, y: 0 },
    submitted: false,
  },
] as const

describe("CanvasViewport", () => {
  it("Given a predicted overlap When keyboard movement finishes Then the server save is still requested", () => {
    const onSave = vi.fn()
    render(<CanvasViewport backgroundUrl={null} canvasHeight={1080} canvasWidth={1920} entries={entries} onSave={onSave} onUnplace={vi.fn()} />)

    fireEvent.keyDown(screen.getByRole("button", { name: "한별 이동" }), { key: "ArrowRight" })

    expect(onSave).toHaveBeenCalledOnce()
    expect(screen.getByTestId("slot-21111111-1111-4111-8111-111111111111")).toHaveClass("slot-overlay--collision")
  })
})
