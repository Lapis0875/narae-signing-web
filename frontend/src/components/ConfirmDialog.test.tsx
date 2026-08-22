import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { useState } from "react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { ConfirmDialog } from "./ConfirmDialog.tsx"

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

describe("ConfirmDialog", () => {
  it("Given an unfocused invoker When cancel closes the dialog Then focus returns to that invoker", async () => {
    render(<Fixture />)
    const trigger = screen.getByRole("button", { name: "열기" })

    fireEvent.click(trigger)
    fireEvent.click(screen.getByRole("button", { name: "취소" }))

    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })
})

function Fixture() {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button onClick={() => setOpen(true)} type="button">열기</button>
      <ConfirmDialog
        confirmLabel="확인"
        message="계속할까요?"
        onCancel={() => setOpen(false)}
        onConfirm={() => setOpen(false)}
        open={open}
      />
    </>
  )
}
