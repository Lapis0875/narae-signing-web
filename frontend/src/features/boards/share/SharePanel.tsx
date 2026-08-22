import encodeQR from "qr"
import { useRef, useState } from "react"
import { ConfirmDialog } from "../../../components/ConfirmDialog.tsx"
import type { Share } from "../editor/editorApi.ts"

type SharePanelProps = {
  readonly disabled: boolean
  readonly onReissue: () => Promise<void>
  readonly share: Share
}

export function SharePanel({ disabled, onReissue, share }: SharePanelProps) {
  const invokerRef = useRef<HTMLButtonElement>(null)
  const [confirming, setConfirming] = useState(false)
  const [copyFailed, setCopyFailed] = useState(false)
  const shareUrl = new URL(`/sign/${encodeURIComponent(share.shareToken)}`, window.location.origin).toString()
  const qr = encodeQR(shareUrl, "raw", { ecc: "medium" })
  const qrSize = qr.length
  const qrPath = qr.flatMap((row, y) => row.map((filled, x) => filled ? `M${x} ${y}h1v1h-1z` : "")).join("")

  return (
    <section className="editor-panel" aria-labelledby="share-title">
      <h2 id="share-title">공유</h2>
      <p className="editor-share-url">{shareUrl}</p>
      <svg
        aria-label="현재 서명 링크 QR"
        className="editor-qr"
        data-share-url={shareUrl}
        role="img"
        viewBox={`-4 -4 ${qrSize + 8} ${qrSize + 8}`}
      >
        <title>현재 서명 링크 QR</title>
        <rect width={qrSize + 8} height={qrSize + 8} x="-4" y="-4" />
        <path className="editor-qr-module" d={qrPath} />
      </svg>
      <div className="editor-actions">
        <button className="board-button" onClick={() => {
          setCopyFailed(false)
          void navigator.clipboard.writeText(shareUrl).catch(() => setCopyFailed(true))
        }} type="button">링크 복사</button>
        <button className="board-button board-button--destructive" disabled={disabled} onClick={() => setConfirming(true)} ref={invokerRef} type="button">링크 재발급</button>
      </div>
      {copyFailed ? <p role="alert">링크를 복사하지 못했습니다. 주소를 직접 선택해 복사해 주세요.</p> : null}
      <ConfirmDialog
        confirmLabel="재발급"
        invokerRef={invokerRef}
        message="기존 링크는 즉시 무효화되어 이전 링크의 서명자는 더 이상 이용할 수 없습니다."
        onCancel={() => setConfirming(false)}
        onConfirm={() => { setConfirming(false); void onReissue() }}
        open={confirming}
      />
    </section>
  )
}
