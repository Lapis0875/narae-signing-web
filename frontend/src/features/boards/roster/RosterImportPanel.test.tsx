import "@testing-library/jest-dom/vitest"
import { cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"
import { RosterImportPanel } from "./RosterImportPanel.tsx"

afterEach(() => cleanup())

describe("RosterImportPanel", () => {
  it("Given valid CSV pasted without preview When applying Then current rows are submitted", () => {
    const onReplace = vi.fn()
    render(<RosterImportPanel isSaving={false} onImportFile={vi.fn()} onReplace={onReplace} />)

    fireEvent.change(screen.getByLabelText("소속, 직책, 이름 순서로 붙여넣기"), {
      target: { value: "나래,담당,한별\n나래,팀장,누리" },
    })

    const apply = screen.getByRole("button", { name: "붙여넣기 적용" })
    expect(apply).toBeEnabled()
    fireEvent.click(apply)

    expect(onReplace).toHaveBeenCalledWith([
      { job: "담당", name: "한별", organization: "나래" },
      { job: "팀장", name: "누리", organization: "나래" },
    ])
  })
})
