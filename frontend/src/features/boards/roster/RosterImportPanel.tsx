import { useState } from "react"
import type { RosterIdentity } from "./rosterApi.ts"
import { previewRosterText, type RosterPreview } from "./rosterPreview.ts"

type RosterImportPanelProps = {
  readonly isSaving: boolean
  readonly onImportFile: (file: File, preview: RosterPreview) => void
  readonly onReplace: (rows: readonly RosterIdentity[]) => void
}

export function RosterImportPanel({ isSaving, onImportFile, onReplace }: RosterImportPanelProps) {
  const [file, setFile] = useState<File | null>(null)
  const [pasteValue, setPasteValue] = useState("")
  const [preview, setPreview] = useState<RosterPreview | null>(null)

  const renderPreview = () => preview === null ? null : (
    <section aria-label="명단 미리보기">
      <p>{preview.rows.length}명 미리보기</p>
      {preview.markers.length > 0 ? (
        <ol aria-label="입력 오류" className="board-marker-list" role="alert">
          {preview.markers.map((marker) => (
            <li data-row={marker.row} key={`${marker.row}-${marker.message}`}>
              {marker.row}행: {marker.message}
            </li>
          ))}
        </ol>
      ) : null}
    </section>
  )

  return (
    <section aria-labelledby="roster-import-title" className="board-import">
      <h2 className="board-section-title" id="roster-import-title">명단 가져오기</h2>
      <label htmlFor="roster-paste">소속, 직책, 이름 순서로 붙여넣기</label>
      <textarea
        className="board-textarea"
        id="roster-paste"
        onChange={(event) => setPasteValue(event.target.value)}
        value={pasteValue}
      />
      <div className="board-actions">
        <button className="board-button" onClick={() => setPreview(previewRosterText(pasteValue))} type="button">붙여넣기 미리보기</button>
        <button
          className="board-button board-button--primary"
          disabled={isSaving || preview === null || preview.markers.length > 0}
          onClick={() => {
            if (preview !== null) {
              onReplace(preview.rows)
            }
          }}
          type="button"
        >붙여넣기 적용</button>
      </div>
      <div className="board-file-field">
        <input
          accept=".csv,.xlsx"
          aria-label="CSV 또는 XLSX 파일 선택"
          className="board-file-input"
          id="roster-file"
          onChange={async (event) => {
            const selected = event.target.files?.[0] ?? null
            setFile(selected)
            if (selected?.name.toLowerCase().endsWith(".csv")) {
              setPreview(previewRosterText(await selected.text()))
            } else {
              setPreview(null)
            }
          }}
          type="file"
        />
        <label className="board-button board-file-trigger" htmlFor="roster-file">CSV 또는 XLSX 파일 선택</label>
        <span aria-live="polite" className="board-file-name">{file?.name ?? "선택한 파일 없음"}</span>
      </div>
      <button
        className="board-button board-button--primary"
        disabled={isSaving || file === null || (preview?.markers.length ?? 0) > 0}
        onClick={() => {
          if (file !== null) {
            onImportFile(file, preview ?? { markers: [], rows: [] })
          }
        }}
        type="button"
      >파일 가져오기</button>
      {renderPreview()}
    </section>
  )
}
