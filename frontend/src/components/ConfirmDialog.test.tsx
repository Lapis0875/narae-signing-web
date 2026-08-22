import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { useRef, useState } from "react"
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

  it("Given a second unopened dialog When it unmounts Then it does not focus the first dialog's trigger", async () => {
    const view = render(<CrossInstanceFixture showSecond />)
    const firstTrigger = screen.getByRole("button", { name: "첫 번째 열기" })
    const neutralTarget = screen.getByRole("button", { name: "중립 대상" })

    fireEvent.click(firstTrigger)
    fireEvent.click(screen.getByRole("button", { name: "취소" }))
    neutralTarget.focus()
    view.rerender(<CrossInstanceFixture showSecond={false} />)

    await waitFor(() => expect(document.activeElement).toBe(neutralTarget))
  })

  it("Given an open dialog When native cancel fires Then focus returns to its invoker", async () => {
    render(<Fixture />)
    const trigger = screen.getByRole("button", { name: "열기" })

    fireEvent.click(trigger)
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }))

    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })

  it("Given an open dialog When it unmounts Then focus returns to its invoker", async () => {
    const view = render(<UnmountFixture showDialog />)
    const trigger = screen.getByRole("button", { name: "열기" })

    fireEvent.click(trigger)
    view.rerender(<UnmountFixture showDialog={false} />)

    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })
})

function CrossInstanceFixture({ showSecond }: { readonly showSecond: boolean }) {
  const firstInvokerRef = useRef<HTMLButtonElement>(null)
  const secondInvokerRef = useRef<HTMLButtonElement>(null)
  const [open, setOpen] = useState(false)
  return (
    <>
      <button onClick={() => setOpen(true)} ref={firstInvokerRef} type="button">첫 번째 열기</button>
      <button ref={secondInvokerRef} type="button">중립 대상</button>
      <ConfirmDialog
        confirmLabel="확인"
        invokerRef={firstInvokerRef}
        message="첫 번째 대화상자"
        onCancel={() => setOpen(false)}
        onConfirm={() => setOpen(false)}
        open={open}
      />
      {showSecond ? (
        <ConfirmDialog
          confirmLabel="확인"
          invokerRef={secondInvokerRef}
          message="열리지 않은 대화상자"
          onCancel={() => undefined}
          onConfirm={() => undefined}
          open={false}
        />
      ) : null}
    </>
  )
}

function UnmountFixture({ showDialog }: { readonly showDialog: boolean }) {
  const invokerRef = useRef<HTMLButtonElement>(null)
  const [open, setOpen] = useState(false)
  return (
    <>
      <button onClick={() => setOpen(true)} ref={invokerRef} type="button">열기</button>
      {showDialog ? (
        <ConfirmDialog
          confirmLabel="확인"
          invokerRef={invokerRef}
          message="계속할까요?"
          onCancel={() => setOpen(false)}
          onConfirm={() => setOpen(false)}
          open={open}
        />
      ) : null}
    </>
  )
}

function Fixture() {
  const invokerRef = useRef<HTMLButtonElement>(null)
  const [open, setOpen] = useState(false)
  return (
    <>
      <button onClick={() => setOpen(true)} ref={invokerRef} type="button">열기</button>
      <ConfirmDialog
        confirmLabel="확인"
        invokerRef={invokerRef}
        message="계속할까요?"
        onCancel={() => setOpen(false)}
        onConfirm={() => setOpen(false)}
        open={open}
      />
    </>
  )
}
