import { createContext, type ReactNode, useContext, useEffect, useMemo, useState } from "react"

type ToastContextValue = {
  readonly showToast: (message: string) => void
}

const ToastContext = createContext<ToastContextValue | null>(null)

class ToastProviderError extends Error {
  constructor() {
    super("useToast must be used inside ToastProvider")
    this.name = "ToastProviderError"
  }
}

type ToastProviderProps = {
  readonly children: ReactNode
  readonly durationMs?: number
}

export function ToastProvider({ children, durationMs = 5_000 }: ToastProviderProps) {
  const [toast, setToast] = useState<{ readonly id: number; readonly message: string } | null>(null)
  const value = useMemo(() => ({
    showToast: (message: string) => setToast((current) => ({ id: (current?.id ?? 0) + 1, message })),
  }), [])

  useEffect(() => {
    if (toast === null) return
    const timeoutId = window.setTimeout(() => setToast(null), durationMs)
    return () => window.clearTimeout(timeoutId)
  }, [durationMs, toast])

  return (
    <ToastContext.Provider value={value}>
      {children}
      <output aria-live="polite" className="app-toast">{toast?.message ?? ""}</output>
    </ToastContext.Provider>
  )
}

export function useToast(): ToastContextValue {
  const context = useContext(ToastContext)
  if (context === null) {
    throw new ToastProviderError()
  }
  return context
}
