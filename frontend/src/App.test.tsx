import "@testing-library/jest-dom/vitest"
import { render, screen } from "@testing-library/react"
import { describe, expect, it } from "vitest"
import { App } from "./App.tsx"

describe("App", () => {
  it("renders the application shell", () => {
    render(<App />)
    expect(screen.getByRole("main")).toHaveTextContent("Narae Signing")
  })
})
