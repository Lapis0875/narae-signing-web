import { rosterIdentitySchema, type RosterIdentity } from "./rosterApi.ts"

export type RosterRowMarker = {
  readonly message: string
  readonly row: number
}

export type RosterPreview = {
  readonly markers: readonly RosterRowMarker[]
  readonly rows: readonly RosterIdentity[]
}

export function previewRosterText(value: string): RosterPreview {
  const rows: RosterIdentity[] = []
  const markers: RosterRowMarker[] = []
  const identities = new Set<string>()

  value.split(/\r?\n/u).forEach((line, index) => {
    if (line.trim().length === 0) {
      return
    }
    const row = index + 1
    const columns = line.includes("\t") ? line.split("\t") : line.split(",")
    const parsed = rosterIdentitySchema.safeParse({
      job: columns[1] ?? "",
      name: columns[2] ?? "",
      organization: columns[0] ?? "",
    })
    if (!parsed.success || columns.length !== 3) {
      markers.push({ message: "이름과 세 필드를 확인해 주세요.", row })
      return
    }
    const identityKey = JSON.stringify(parsed.data)
    if (identities.has(identityKey)) {
      markers.push({ message: "같은 명단이 중복되었습니다.", row })
      return
    }
    identities.add(identityKey)
    rows.push(parsed.data)
  })

  if (rows.length + markers.length > 50) {
    markers.push({ message: "명단은 50명까지 등록할 수 있습니다.", row: 51 })
  }
  return { markers: markers.sort((left, right) => left.row - right.row), rows }
}
