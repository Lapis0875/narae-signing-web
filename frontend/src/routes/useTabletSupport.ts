import { useSyncExternalStore } from "react"

function subscribe(onStoreChange: () => void): () => void {
  window.addEventListener("resize", onStoreChange)
  return () => window.removeEventListener("resize", onStoreChange)
}

function supportsSigning(): boolean {
  return Math.min(window.innerWidth, window.innerHeight) >= 600
}

export function useTabletSupport(): boolean {
  return useSyncExternalStore(subscribe, supportsSigning, () => true)
}
