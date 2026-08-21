import { type FormEvent, useEffect, useState } from "react"
import { ApiError } from "../../../api/errors.ts"
import { submitSignature } from "../api/signatureSubmitApi.ts"
import type { SignaturePayload } from "../pad/signaturePayload.ts"
import { PublicSignerContent } from "./PublicSignerContent.tsx"
import {
  identifySigner,
  type PublicLink,
  readPublicLink,
  readSigningSession,
} from "./publicSignerApi.ts"
import "./PublicSignerFlow.css"

export type SignerView =
  | { readonly kind: "loading" }
  | { readonly kind: "forbidden" }
  | { readonly kind: "error" }
  | { readonly kind: "setup"; readonly title: string }
  | { readonly kind: "invalid" }
  | { readonly kind: "closed"; readonly title: string }
  | { readonly kind: "identify"; readonly message: string; readonly title: string }
  | { readonly kind: "identified"; readonly title: string }
  | {
      readonly kind: "drawing"
      readonly signatureAspectRatio?: number
      readonly title: string
    }
  | { readonly kind: "complete"; readonly title: string }

const emptyMessage = ""
const reidentifyMessage = "서명 정보를 다시 입력해 주세요."

function identifyView(title: string, message = emptyMessage): SignerView {
  return { kind: "identify", message, title }
}

function viewFromSession(
  title: string,
  session: Awaited<ReturnType<typeof readSigningSession>>,
): SignerView {
  switch (session.state) {
    case "READY":
      return session.signatureAspectRatio === undefined
        ? { kind: "drawing", title }
        : { kind: "drawing", signatureAspectRatio: session.signatureAspectRatio, title }
    case "SUBMITTED":
      return { kind: "complete", title }
    case "CLOSED":
      return { kind: "closed", title }
    case "STALE":
      return identifyView(title, reidentifyMessage)
    case "INVALID":
      return { kind: "invalid" }
  }
}

function viewFromLink(link: PublicLink): SignerView | null {
  switch (link.state) {
    case "SETUP":
      return { kind: "setup", title: link.title }
    case "CLOSED":
      return { kind: "closed", title: link.title }
    case "INVALID":
      return { kind: "invalid" }
    case "OPEN":
      return null
  }
}

type PublicSignerFlowProps = {
  readonly shareToken: string
}

export function PublicSignerFlow({ shareToken }: PublicSignerFlowProps) {
  const [view, setView] = useState<SignerView>({ kind: "loading" })

  useEffect(() => {
    let active = true
    void Promise.resolve().then(async () => {
      if (!active) {
        return
      }
      try {
        const link = await readPublicLink(shareToken)
        if (!active) {
          return
        }
        const terminalView = viewFromLink(link)
        if (terminalView !== null) {
          setView(terminalView)
          return
        }
        if (link.state !== "OPEN") {
          setView({ kind: "invalid" })
          return
        }
        try {
          const session = await readSigningSession()
          if (active) {
            setView(viewFromSession(link.title, session))
          }
        } catch (error) {
          if (!active) {
            return
          }
          if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
            setView(identifyView(link.title))
            return
          }
          setView({ kind: "invalid" })
        }
      } catch (error) {
        if (active) {
          if (error instanceof ApiError && error.status === 403) {
            setView({ kind: "forbidden" })
          } else if (error instanceof ApiError && error.status >= 500) {
            setView({ kind: "error" })
          } else {
            setView({ kind: "invalid" })
          }
        }
      }
    })
    return () => {
      active = false
    }
  }, [shareToken])

  const identify = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = event.currentTarget
    const data = new FormData(form)
    const organization = data.get("organization")
    const job = data.get("job")
    const name = data.get("name")
    if (
      typeof organization !== "string" ||
      typeof job !== "string" ||
      typeof name !== "string" ||
      name.length === 0
    ) {
      setView((current) => current.kind === "identify"
        ? { ...current, message: "이름을 입력해 주세요." }
        : current)
      return
    }
    if (view.kind !== "identify") {
      return
    }
    const title = view.title
    setView({ kind: "identified", title })
    try {
      await identifySigner(shareToken, { organization, job, name })
      const session = await readSigningSession()
      setView(viewFromSession(title, session))
    } catch {
      setView({
        kind: "identify",
        message: "입력 정보를 확인한 뒤 다시 시도해 주세요.",
        title,
      })
    }
  }

  const submit = async (payload: SignaturePayload): Promise<boolean> => {
    try {
      await submitSignature(payload.strokes)
      setView((current) => current.kind === "drawing"
        ? { kind: "complete", title: current.title }
        : current)
      return true
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.code === "BOARD_CLOSED") {
          setView((current) => ({
            kind: "closed",
            title: "title" in current ? current.title : "서명하기",
          }))
          return false
        }
        if (
          error.code === "SIGNER_STALE" ||
          error.code === "UNAUTHORIZED" ||
          error.code === "FORBIDDEN"
        ) {
          setView((current) => "title" in current
            ? identifyView(current.title, reidentifyMessage)
            : current)
          return false
        }
        if (error.code === "CONFLICT") {
          try {
            const session = await readSigningSession()
            setView((current) => "title" in current
              ? viewFromSession(current.title, session)
              : current)
            return session.state === "SUBMITTED"
          } catch (sessionError) {
            if (
              sessionError instanceof ApiError &&
              (sessionError.status === 401 || sessionError.status === 403)
            ) {
              setView((current) => "title" in current
                ? identifyView(current.title, reidentifyMessage)
                : current)
              return false
            }
            setView({ kind: "invalid" })
            return false
          }
        }
      }
      return false
    }
  }

  return <PublicSignerContent identify={identify} submit={submit} view={view} />
}
