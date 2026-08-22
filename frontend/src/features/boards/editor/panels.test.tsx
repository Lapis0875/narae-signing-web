import "@testing-library/jest-dom/vitest"
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"
import { BackgroundPanel } from "../background/BackgroundPanel.tsx"
import { SharePanel } from "../share/SharePanel.tsx"

afterEach(cleanup)

describe("BackgroundPanel", () => {
  it("Given a selected file When upload is rejected Then the selection is retained", async () => {
    const rejected = vi.fn(async () => { throw new Error("rejected") })
    render(<BackgroundPanel disabled={false} onUpload={rejected} />)
    const input = screen.getByLabelText("PNG 또는 JPEG")
    const file = new File(["png"], "background.png", { type: "image/png" })

    fireEvent.change(input, { target: { files: { 0: file, item: () => file, length: 1 } } })
    fireEvent.click(screen.getByRole("button", { name: "기존 비율로 교체" }))

    await waitFor(() => expect(rejected).toHaveBeenCalledOnce())
    expect(input).toHaveProperty("files.0.name", "background.png")
  })
})

describe("SharePanel", () => {
  it("Given clipboard rejection When copy is activated Then a visible generic failure is shown", async () => {
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: vi.fn(async () => { throw new DOMException("denied") }) } })
    render(<SharePanel disabled={false} onReissue={async () => undefined} share={{ shareToken: "safe-share", version: 1 }} />)

    fireEvent.click(screen.getByRole("button", { name: "링크 복사" }))

    expect(await screen.findByRole("alert")).toHaveTextContent("링크를 복사하지 못했습니다")
  })
})
