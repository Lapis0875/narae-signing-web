import type { ReactNode } from "react";
import { ApiError } from "../api/errors.ts";

export function LoadingView() {
  return (
    <section
      aria-busy="true"
      className="app-panel async-view"
      data-testid="loading-view"
    >
      <p role="status">불러오는 중입니다.</p>
    </section>
  );
}

export function ForbiddenView() {
  return (
    <section
      className="app-panel async-view"
      data-testid="forbidden-view"
      role="alert"
    >
      <h1>접근할 수 없습니다.</h1>
      <p>권한을 확인하거나 다시 로그인해 주세요.</p>
    </section>
  );
}

export function ErrorView() {
  return (
    <section
      className="app-panel async-view"
      data-testid="error-view"
      role="alert"
    >
      <h1>화면을 불러오지 못했습니다.</h1>
      <p>잠시 후 다시 시도해 주세요.</p>
    </section>
  );
}

type AuthGuardProps = {
  readonly children: ReactNode;
  readonly error: Error | null;
  readonly isPending: boolean;
};

export function AuthGuard({ children, error, isPending }: AuthGuardProps) {
  if (isPending) {
    return <LoadingView />;
  }
  if (
    error instanceof ApiError &&
    (error.status === 401 || error.status === 403)
  ) {
    return <ForbiddenView />;
  }
  if (error !== null) {
    return <ErrorView />;
  }
  return children;
}
