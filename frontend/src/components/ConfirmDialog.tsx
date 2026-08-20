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
  return (
    <dialog aria-labelledby="confirm-dialog-title" open={open}>
      <h2 id="confirm-dialog-title">확인</h2>
      <p>{message}</p>
      <button onClick={onCancel} type="button">
        취소
      </button>
      <button onClick={onConfirm} type="button">
        {confirmLabel}
      </button>
    </dialog>
  )
}
