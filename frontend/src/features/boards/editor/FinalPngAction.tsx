import { useQuery } from "@tanstack/react-query"
import { useState } from "react"
import { useParams } from "react-router-dom"
import { fetchBoardDetail } from "./editorApi.ts"

class FinalPngDownloadError extends Error {}

export function FinalPngAction() {
  const { boardId = "missing" } = useParams()
  const board = useQuery({
    queryFn: () => fetchBoardDetail(boardId),
    queryKey: ["admin", "boards", boardId],
  })
  const [state, setState] = useState<"idle" | "downloading" | "failed">("idle")
  const label = state === "failed" ? "다시 시도" : state === "downloading" ? "PNG 만드는 중" : "최종 PNG 다운로드"
  const download = async () => {
    setState("downloading")
    try {
      const response = await fetch(`/api/v1/admin/boards/${boardId}/final.png`, {
        cache: "no-store",
        credentials: "same-origin",
        referrerPolicy: "no-referrer",
      })
      if (!response.ok) throw new FinalPngDownloadError()
      const blob = await response.blob()
      if (blob.size === 0 || !blob.type.startsWith("image/png")) throw new FinalPngDownloadError()
      const url = URL.createObjectURL(blob)
      try {
        const anchor = document.createElement("a")
        anchor.href = url
        anchor.download = "board-final.png"
        anchor.click()
      } finally {
        URL.revokeObjectURL(url)
      }
      setState("idle")
    } catch (error) {
      if (error instanceof FinalPngDownloadError || error instanceof TypeError) {
        setState("failed")
        return
      }
      throw error
    }
  }

  return (
    <fieldset
      aria-label="최종 PNG 작업"
      style={{
        border: 0,
        display: "inline-grid",
        margin: 0,
        maxInlineSize: "100%",
        minInlineSize: 0,
        padding: `0 0 ${state === "failed" ? "calc(var(--space-48) + var(--space-8))" : "0"}`,
        position: "relative",
      }}
    >
      <button
        className="board-button board-button--primary"
        disabled={board.data?.status !== "마감/보관" || state === "downloading"}
        onClick={() => void download()}
        type="button"
      >
        <span style={{ display: "grid" }}>
          <span aria-hidden="true" style={{ gridArea: "1 / 1", visibility: "hidden" }}>최종 PNG 다운로드</span>
          <span style={{ gridArea: "1 / 1" }}>{label}</span>
        </span>
      </button>
      {state === "failed" ? (
        <span
          role="alert"
          style={{
            insetBlockStart: "calc(var(--control-height) + var(--space-32))",
            insetInlineEnd: 0,
            maxInlineSize: "calc(100vw - var(--space-64))",
            overflowWrap: "anywhere",
            position: "absolute",
            whiteSpace: "nowrap",
          }}
        >
          최종 PNG를 다운로드하지 못했습니다.
        </span>
      ) : null}
    </fieldset>
  )
}
