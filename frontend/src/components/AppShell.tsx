import { useSyncExternalStore } from "react";
import type { ReactNode } from "react";
import { createPortal } from "react-dom";

let appHeaderActionsTarget: HTMLDivElement | null = null;
const appHeaderActionsListeners = new Set<() => void>();

function getAppHeaderActionsTarget() {
  return appHeaderActionsTarget;
}

function subscribeToAppHeaderActions(listener: () => void) {
  appHeaderActionsListeners.add(listener);
  return () => appHeaderActionsListeners.delete(listener);
}

function setAppHeaderActionsTarget(target: HTMLDivElement | null) {
  appHeaderActionsTarget = target;
  for (const listener of appHeaderActionsListeners) {
    listener();
  }
}

type AppHeaderActionsProps = {
  readonly children: ReactNode;
};

export function AppHeaderActions({ children }: AppHeaderActionsProps) {
  const target = useSyncExternalStore(subscribeToAppHeaderActions, getAppHeaderActionsTarget, () => null);
  return target === null ? null : createPortal(children, target);
}

type AppShellProps = {
  readonly children: ReactNode;
};

export function AppShell({ children }: AppShellProps) {
  return (
    <div className="app-shell">
      <header className="app-header">
        <a href="/boards">나래 서명 보드</a>
        <div className="app-header-actions" ref={setAppHeaderActionsTarget} />
      </header>
      <main className="app-main">{children}</main>
    </div>
  );
}
