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
}

export function ToastProvider({ children }: ToastProviderProps) {
  const [message, setMessage] = useState("")
  const value = useMemo(() => ({ showToast: setMessage }), [])

  useEffect(() => {
    if (message === "") return
    const timeoutId = window.setTimeout(() => setMessage(""), 5_000)
    return () => window.clearTimeout(timeoutId)
  }, [message])

  return (
    <ToastContext.Provider value={value}>
      {children}
      <output aria-live="polite" className="app-toast">{message}</output>
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
