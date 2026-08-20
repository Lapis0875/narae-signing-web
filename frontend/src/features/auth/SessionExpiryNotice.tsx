import { useEffect, useState } from "react"

const warningWindowMs = 30 * 60_000

type SessionExpiryNoticeProps = {
  readonly expiresAt: string
}

export function SessionExpiryNotice({ expiresAt }: SessionExpiryNoticeProps) {
  const expiry = new Date(expiresAt).getTime()
  const [now, setNow] = useState(Date.now())
  const remainingMs = expiry - now

  useEffect(() => {
    const untilWarning = expiry - Date.now() - warningWindowMs
    const warningTimer = window.setTimeout(() => setNow(Date.now()), Math.max(0, untilWarning))
    const minuteTimer = window.setInterval(() => setNow(Date.now()), 60_000)
    return () => {
      window.clearTimeout(warningTimer)
      window.clearInterval(minuteTimer)
    }
  }, [expiry])

  if (remainingMs <= 0 || remainingMs > warningWindowMs) {
    return null
  }

  return (
    <p data-testid="session-expiry-notice" role="status">
      세션 만료까지 약 {Math.ceil(remainingMs / 60_000)}분 남았습니다. 작업을 저장해 주세요.
    </p>
  )
}
