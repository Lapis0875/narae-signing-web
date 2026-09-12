import "@testing-library/jest-dom/vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import type { Board } from "../list/boardApi.ts";
import { BoardToolbar } from "./BoardToolbar.tsx";

const board: Board = {
  canvasHeight: 1080,
  canvasWidth: 1920,
  createdAt: "2026-08-21T00:00:00.000Z",
  id: "11111111-1111-4111-8111-111111111111",
  shareLinkVersion: 1,
  signatureInkColor: "black",
  status: "설정 중",
  title: "가을 서명 발표회",
  updatedAt: "2026-08-21T00:00:00.000Z",
};

function KeyboardToolbar() {
  const [color, setColor] = useState<Board["signatureInkColor"]>("black");
  return (
    <BoardToolbar
      board={board}
      disabled={false}
      inkColor={color}
      inkColorHelp="색상 안내"
      inkColorLocked={false}
      onInkColorChange={setColor}
      onRename={vi.fn()}
      onTransition={vi.fn()}
      saveState="idle"
      transitionDisabled={false}
      whiteDisabled={false}
    />
  );
}

describe("BoardToolbar signature color keyboard behavior", () => {
  it("Given keyboard focus When Arrow and Space are pressed Then focus and selection follow the native radio pattern", () => {
    // Given
    render(<KeyboardToolbar />);
    expect(
      screen.getByRole("radiogroup", { name: "서명 색상" }),
    ).toHaveAccessibleDescription("색상 안내");
    const black = screen.getByRole("radio", { name: "검정" });
    const white = screen.getByRole("radio", { name: "흰색" });
    black.focus();
    expect(black).toHaveFocus();

    // When
    fireEvent.keyDown(black, { key: "ArrowRight" });

    // Then
    expect(white).toHaveFocus();
    expect(white).toBeChecked();

    // When
    black.focus();
    fireEvent.keyDown(black, { key: " " });

    // Then
    expect(black).toHaveFocus();
    expect(black).toBeChecked();
  });
});
