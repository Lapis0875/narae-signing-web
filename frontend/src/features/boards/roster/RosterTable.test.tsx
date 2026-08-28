import "@testing-library/jest-dom/vitest"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { RosterTable } from "./RosterTable.tsx"

const entry = {
  id: "31111111-1111-4111-8111-111111111111",
  identity: { job: "담당", name: "한별", organization: "나래" },
  slot: {
    backgroundColor: "transparent",
    height: 0.2,
    id: "21111111-1111-4111-8111-111111111111",
    placementStatus: "PLACED",
    revision: 1,
    width: 0.2,
    x: 0,
    y: 0,
  },
  submitted: true,
} as const

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
    configurable: true,
    value: vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute("open", "")
    }),
  })
  Object.defineProperty(HTMLDialogElement.prototype, "close", {
    configurable: true,
    value: vi.fn(function (this: HTMLDialogElement) {
      this.removeAttribute("open")
    }),
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  Reflect.deleteProperty(HTMLDialogElement.prototype, "close")
  Reflect.deleteProperty(HTMLDialogElement.prototype, "showModal")
})

describe("RosterTable", () => {
  it("Given a submitted signer When clearing only the signature Then its slot is reset without deleting the roster entry", () => {
    const onDelete = vi.fn()
    const onResetSignature = vi.fn()
    render(
      <RosterTable
        entries={[entry]}
        isSaving={false}
        onDelete={onDelete}
        onResetSignature={onResetSignature}
        onSave={vi.fn()}
      />,
    )

    fireEvent.click(screen.getByRole("button", { name: "서명만 지우기" }))
    expect(screen.getByRole("dialog")).toHaveTextContent("제출된 서명만 지우고 명단과 배치는 유지할까요?")
    fireEvent.click(screen.getByRole("button", { name: "서명 지우기" }))

    expect(onResetSignature).toHaveBeenCalledWith(entry.slot.id)
    expect(onDelete).not.toHaveBeenCalled()
  })
})
