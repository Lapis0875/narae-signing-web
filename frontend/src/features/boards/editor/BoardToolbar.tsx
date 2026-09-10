import type { ReactNode } from "react";
import type { Board } from "../list/boardApi.ts";
import type { SaveState } from "./saveQueue.ts";

type BoardToolbarProps = {
  readonly actionExtensions?: ReactNode;
  readonly board: Board;
  readonly disabled: boolean;
  readonly inkColor: Board["signatureInkColor"];
  readonly inkColorHelp: string;
  readonly inkColorLocked: boolean;
  readonly onInkColorChange: (color: Board["signatureInkColor"]) => void;
  readonly onRename: (title: string) => Promise<void>;
  readonly onTransition: (action: "open" | "close" | "reopen") => Promise<void>;
  readonly saveState: SaveState;
  readonly transitionDisabled: boolean;
  readonly whiteDisabled: boolean;
};

const saveLabels: Record<SaveState, string> = {
  failed: "저장 실패",
  idle: "변경 없음",
  saved: "저장됨",
  saving: "저장 중",
};

export function BoardToolbar({
  actionExtensions,
  board,
  disabled,
  inkColor,
  inkColorHelp,
  inkColorLocked,
  onInkColorChange,
  onRename,
  onTransition,
  saveState,
  transitionDisabled,
  whiteDisabled,
}: BoardToolbarProps) {
  const moveInkColor = (direction: "next" | "previous") => {
    const nextColor = direction === "next" ? "white" : "black";
    if (nextColor === "white" && whiteDisabled) return;
    onInkColorChange(nextColor);
    document
      .querySelector<HTMLInputElement>(
        `input[name="signature-ink-color"][value="${nextColor}"]`,
      )
      ?.focus();
  };

  return (
    <header className="editor-toolbar">
      <form
        className="editor-title-form"
        onSubmit={(event) => {
          event.preventDefault();
          const title = new FormData(event.currentTarget).get("title");
          if (typeof title === "string" && title.trim().length > 0)
            void onRename(title);
        }}
      >
        <input
          aria-label="보드 제목"
          defaultValue={board.title}
          disabled={disabled}
          name="title"
        />
        <button className="board-button" disabled={disabled} type="submit">
          제목 저장
        </button>
      </form>
      <span className="editor-status">{board.status}</span>
      <output aria-label="자동 저장 상태">{saveLabels[saveState]}</output>
      <div className="editor-actions">
        <div
          aria-disabled={inkColorLocked}
          aria-describedby="signature-ink-help"
          aria-labelledby="signature-ink-label"
          className="editor-ink-colors"
          onKeyDown={(event) => {
            if (event.key === "ArrowRight" || event.key === "ArrowDown") {
              event.preventDefault();
              moveInkColor("next");
            } else if (event.key === "ArrowLeft" || event.key === "ArrowUp") {
              event.preventDefault();
              moveInkColor("previous");
            } else if (
              event.key === " " &&
              event.target instanceof HTMLInputElement &&
              !event.target.checked
            ) {
              event.preventDefault();
              onInkColorChange(
                event.target.value === "white" ? "white" : "black",
              );
            }
          }}
          role="radiogroup"
        >
          <span className="editor-ink-title" id="signature-ink-label">
            서명 색상
          </span>
          <label>
            <input
              checked={inkColor === "black"}
              disabled={inkColorLocked}
              name="signature-ink-color"
              onChange={() => onInkColorChange("black")}
              type="radio"
              value="black"
            />
            <span
              aria-hidden="true"
              className="editor-ink-swatch editor-ink-swatch--black"
            />
            검정
          </label>
          <label>
            <input
              checked={inkColor === "white"}
              disabled={inkColorLocked || whiteDisabled}
              name="signature-ink-color"
              onChange={() => onInkColorChange("white")}
              type="radio"
              value="white"
            />
            <span
              aria-hidden="true"
              className="editor-ink-swatch editor-ink-swatch--white"
            />
            흰색
          </label>
          <small id="signature-ink-help">{inkColorHelp}</small>
        </div>
        {board.status === "설정 중" ? (
          <button
            className="board-button board-button--primary"
            disabled={transitionDisabled}
            onClick={() => void onTransition("open")}
            type="button"
          >
            서명 시작
          </button>
        ) : null}
        {board.status === "서명 진행" ? (
          <button
            className="board-button board-button--destructive"
            disabled={transitionDisabled}
            onClick={() => void onTransition("close")}
            type="button"
          >
            마감
          </button>
        ) : null}
        {board.status === "마감/보관" ? (
          <button
            className="board-button board-button--primary"
            disabled={transitionDisabled}
            onClick={() => void onTransition("reopen")}
            type="button"
          >
            다시 열기
          </button>
        ) : null}
        {actionExtensions}
      </div>
    </header>
  );
}
