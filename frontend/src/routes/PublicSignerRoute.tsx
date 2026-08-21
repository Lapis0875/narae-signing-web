import { useParams } from "react-router-dom"
import { UnsupportedDeviceView } from "../components/UnsupportedDeviceView.tsx"
import { PublicSignerFlow } from "../features/signing/flow/PublicSignerFlow.tsx"
import { useTabletSupport } from "./useTabletSupport.ts"

export function PublicSignerRoute() {
  const { shareToken = "" } = useParams()
  const isSupported = useTabletSupport()

  if (!isSupported) {
    return <UnsupportedDeviceView />
  }
  return <PublicSignerFlow shareToken={shareToken} />
}
