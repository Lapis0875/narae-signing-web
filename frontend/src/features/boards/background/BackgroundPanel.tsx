import { useRef, useState } from "react"
import { ConfirmDialog } from "../../../components/ConfirmDialog.tsx"

type BackgroundPanelProps = {
  readonly disabled: boolean
  readonly onUpload: (file: File, adoptSourceRatio: boolean) => Promise<void>
}

export function BackgroundPanel({ disabled, onUpload }: BackgroundPanelProps) {
  const inputRef = useRef<HTMLInputElement>(null)
  const ratioInvokerRef = useRef<HTMLButtonElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [confirmingRatio, setConfirmingRatio] = useState(false)
  const [uploadFailed, setUploadFailed] = useState(false)

  const upload = async (adoptSourceRatio: boolean) => {
    if (file !== null) {
      setUploadFailed(false)
      try {
        await onUpload(file, adoptSourceRatio)
        setFile(null)
        if (inputRef.current !== null) inputRef.current.value = ""
      } catch {
        setUploadFailed(true)
      }
    }
  }

  return (
    <section className="editor-panel" aria-labelledby="background-title">
      <h2 id="background-title">배경</h2>
      <label className="editor-file-field">
        PNG 또는 JPEG
        <input
          accept="image/png,image/jpeg"
          disabled={disabled}
          onChange={(event) => setFile(event.target.files?.item(0) ?? null)}
          ref={inputRef}
          type="file"
        />
      </label>
      <div className="editor-actions">
        <button className="board-button" disabled={disabled || file === null} onClick={() => void upload(false)} type="button">
          기존 비율로 교체
        </button>
        <button className="board-button" disabled={disabled || file === null} onClick={() => setConfirmingRatio(true)} ref={ratioInvokerRef} type="button">
          이미지 비율 적용
        </button>
      </div>
      {uploadFailed ? <p role="alert">배경을 교체하지 못했습니다. 선택한 파일을 확인해 주세요.</p> : null}
      <ConfirmDialog
        confirmLabel="비율 적용"
        invokerRef={ratioInvokerRef}
        message="캔버스 비율이 바뀝니다. 정규화된 칸은 상대 위치와 크기를 유지합니다."
        onCancel={() => setConfirmingRatio(false)}
        onConfirm={() => { setConfirmingRatio(false); void upload(true) }}
        open={confirmingRatio}
      />
    </section>
  )
}
