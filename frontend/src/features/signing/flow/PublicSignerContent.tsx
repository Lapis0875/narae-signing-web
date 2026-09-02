import type { CSSProperties, FormEvent } from "react"
import { AppShell } from "../../../components/AppShell.tsx"
import { ErrorView, ForbiddenView, LoadingView } from "../../../components/AsyncViews.tsx"
import { SignaturePad } from "../pad/index.ts"
import type { SignaturePayload } from "../pad/signaturePayload.ts"
import type { SignerView } from "./PublicSignerFlow.tsx"

type PublicSignerContentProps = {
  readonly cancel: () => Promise<boolean>
  readonly clearDraft: () => Promise<boolean>
  readonly draft: (payload: SignaturePayload) => Promise<boolean>
  readonly identify: (event: FormEvent<HTMLFormElement>) => void
  readonly submit: (payload: SignaturePayload) => Promise<boolean>
  readonly view: SignerView
}

type SignatureAspectStyle = CSSProperties & {
  readonly "--public-signer-signature-aspect": number
}

function signatureAspectStyle(ratio: number): SignatureAspectStyle {
  return { "--public-signer-signature-aspect": ratio }
}

export function PublicSignerContent({ cancel, clearDraft, draft, identify, submit, view }: PublicSignerContentProps) {
  if (view.kind === "loading") {
    return <LoadingView />
  }
  if (view.kind === "forbidden") {
    return <AppShell><ForbiddenView /></AppShell>
  }
  if (view.kind === "error") {
    return <AppShell><ErrorView /></AppShell>
  }
  if (view.kind === "invalid") {
    return (
      <StatePanel testId="public-signer-invalid" title="서명을 진행할 수 없습니다.">
        이 링크에서는 서명을 진행할 수 없습니다.
      </StatePanel>
    )
  }
  if (view.kind === "setup") {
    return (
      <StatePanel testId="public-signer-setup" title={view.title}>
        아직 서명 준비 중입니다. 잠시 후 다시 확인해 주세요.
      </StatePanel>
    )
  }
  if (view.kind === "closed") {
    return (
      <StatePanel testId="public-signer-closed" title={view.title}>
        서명이 마감되었습니다.
      </StatePanel>
    )
  }
  if (view.kind === "busy") {
    return (
      <StatePanel testId="public-signer-busy" title={view.title}>
        다른 기기에서 이 서명 칸을 작성 중입니다. 잠시 후 다시 시도해 주세요.
      </StatePanel>
    )
  }
  if (view.kind === "complete") {
    return (
      <StatePanel testId="public-signer-complete" title={view.title}>
        이미 서명을 제출했습니다. 감사합니다.
      </StatePanel>
    )
  }
  if (view.kind === "identified") {
    return (
      <StatePanel busy testId="public-signer-identified" title={view.title}>
        정보를 확인했습니다. 서명 화면을 준비하고 있습니다.
      </StatePanel>
    )
  }
  if (view.kind === "identify") {
    return (
      <AppShell>
        <section className="app-panel route-panel public-signer" data-testid="public-signer-identify">
          <h1>{view.title}</h1>
          <p>명단에 등록된 정보를 공백까지 정확하게 입력해 주세요.</p>
          <form className="public-signer__form" onSubmit={identify}>
            <label htmlFor="signer-organization">소속사 (선택)</label>
            <input autoComplete="organization" id="signer-organization" name="organization" />
            <label htmlFor="signer-job">직책 (선택)</label>
            <input autoComplete="organization-title" id="signer-job" name="job" />
            <label htmlFor="signer-name">이름</label>
            <input autoComplete="name" id="signer-name" name="name" required />
            <button className="app-primary-button public-signer__action" type="submit">
              정보 확인
            </button>
          </form>
          <p aria-live="polite" className="public-signer__message" role="status">{view.message}</p>
        </section>
      </AppShell>
    )
  }
  return (
    <AppShell>
      <section className="public-signer" data-testid="public-signer-drawing">
        <div className="app-panel route-panel public-signer__intro">
          <h1>{view.title}</h1>
          <p className="public-signer__identity" data-testid="public-signer-identity">
            서명자: {[view.identity.organization, view.identity.job, view.identity.name].filter((value) => value.length > 0).join(" · ")}
          </p>
          <p>아래 영역에 서명한 뒤 제출해 주세요.</p>
          <p className="public-signer__retention" data-testid="retention-notice">
            입력한 정보와 서명은 이 행사 보드에 저장되며, 관리자가 보드를 삭제할 때까지 보관됩니다.
          </p>
        </div>
        {view.signatureAspectRatio === undefined ? (
          <SignaturePad onCancel={cancel} onClearDraft={clearDraft} onDraft={draft} onSubmit={submit} />
        ) : (
          <div
            className="public-signer__proportional-pad"
            style={signatureAspectStyle(view.signatureAspectRatio)}
          >
            <SignaturePad onCancel={cancel} onClearDraft={clearDraft} onDraft={draft} onSubmit={submit} />
          </div>
        )}
      </section>
    </AppShell>
  )
}

type StatePanelProps = {
  readonly busy?: boolean
  readonly children: string
  readonly testId: string
  readonly title: string
}

function StatePanel({ busy = false, children, testId, title }: StatePanelProps) {
  return (
    <AppShell>
      <section
        aria-busy={busy || undefined}
        className="app-panel route-panel public-signer"
        data-testid={testId}
      >
        <h1>{title}</h1>
        <p role={busy ? "status" : undefined}>{children}</p>
      </section>
    </AppShell>
  )
}
