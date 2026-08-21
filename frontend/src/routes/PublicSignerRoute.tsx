import { useParams } from "react-router-dom"
import { UnsupportedDeviceView } from "../components/UnsupportedDeviceView.tsx"
import { RouteShell } from "./RouteShell.tsx"
import { useTabletSupport } from "./useTabletSupport.ts"

export function PublicSignerRoute() {
  const { shareToken = "missing" } = useParams()
  const isSupported = useTabletSupport()

  if (!isSupported) {
    return <UnsupportedDeviceView />
  }
  return (
    <RouteShell endpoint={`/api/v1/public/sign/${shareToken}`} title="서명하기">
      <canvas aria-label="서명 입력 영역" data-testid="signer-canvas" />
    </RouteShell>
  )
}
