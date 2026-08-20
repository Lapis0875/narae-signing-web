import type { ReactNode } from "react";

type AppShellProps = {
  readonly children: ReactNode;
};

export function AppShell({ children }: AppShellProps) {
  return (
    <div className="app-shell">
      <header className="app-header">
        <a href="/boards">나래 서명 보드</a>
      </header>
      <main className="app-main">{children}</main>
    </div>
  );
}
