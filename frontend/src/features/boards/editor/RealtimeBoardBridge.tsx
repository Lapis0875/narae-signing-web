import { useBoardRealtime } from "../fullview/realtime.ts"

type RealtimeBoardBridgeProps = {
  readonly boardId: string
  readonly refetchSnapshot: () => Promise<unknown>
}

export function RealtimeBoardBridge({ boardId, refetchSnapshot }: RealtimeBoardBridgeProps) {
  useBoardRealtime(boardId, refetchSnapshot, { includeDrafts: false })
  return null
}
