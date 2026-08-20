import "@testing-library/jest-dom/vitest";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AppShell } from "../components/AppShell.tsx";

describe("design foundation", () => {
  it("renders the shared shell and panel primitives", () => {
    render(
      <AppShell>
        <section className="app-panel">
          <h1>관리자 로그인</h1>
        </section>
      </AppShell>,
    );
    expect(screen.getByRole("banner")).toHaveClass("app-header");
    expect(screen.getByRole("main")).toHaveClass("app-main");
    expect(
      screen.getByRole("heading", { name: "관리자 로그인" }).closest("section"),
    ).toHaveClass("app-panel");
  });
});
