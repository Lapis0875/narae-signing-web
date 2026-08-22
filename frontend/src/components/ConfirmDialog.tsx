import { useEffect, useRef } from "react"

type ConfirmDialogProps = {
  readonly confirmLabel: string
  readonly message: string
  readonly onCancel: () => void
  readonly onConfirm: () => void
  readonly open: boolean
}

export function ConfirmDialog({
  confirmLabel,
  message,
  onCancel,
  onConfirm,
  open,
}: ConfirmDialogProps) {
  const cancelButtonRef = useRef<HTMLButtonElement>(null)
  const dialogRef = useRef<HTMLDialogElement>(null)
  const returnFocusRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    const rememberInvoker = (event: MouseEvent) => {
      const target = event.target
      const invoker = target instanceof HTMLElement
        ? target.closest<HTMLElement>("button, [href], input, select, textarea, [tabindex]")
        : null
      if (invoker !== null && !dialogRef.current?.contains(invoker)) {
        returnFocusRef.current = invoker
      }
    }
    document.addEventListener("click", rememberInvoker, true)
    return () => document.removeEventListener("click", rememberInvoker, true)
  }, [])

  useEffect(() => {
    const dialog = dialogRef.current
    if (dialog === null) {
      return
    }
    if (open) {
      if (!dialog.open) {
        if (document.activeElement instanceof HTMLElement && document.activeElement !== document.body) {
          returnFocusRef.current = document.activeElement
        }
        dialog.showModal()
        cancelButtonRef.current?.focus()
      }
      return
    }
    if (dialog.open) {
      dialog.close()
    }
    const returnFocus = returnFocusRef.current
    returnFocusRef.current = null
    if (returnFocus?.isConnected) {
      returnFocus.focus()
    }
  }, [open])

  useEffect(() => () => {
    if (dialogRef.current?.open) {
      dialogRef.current.close()
    }
    if (returnFocusRef.current?.isConnected) {
      returnFocusRef.current.focus()
    }
  }, [])

  return (
    <dialog
      aria-labelledby="confirm-dialog-title"
      onCancel={(event) => {
        event.preventDefault()
        onCancel()
      }}
      ref={dialogRef}
    >
      <h2 id="confirm-dialog-title">확인</h2>
      <p>{message}</p>
      <button onClick={onCancel} ref={cancelButtonRef} type="button">
        취소
      </button>
      <button onClick={onConfirm} type="button">
        {confirmLabel}
      </button>
    </dialog>
  )
}
