import { z } from "zod"
import { apiRequest } from "../../../api/client.ts"
import { ApiError, describeError } from "../../../api/errors.ts"

export const rosterIdentitySchema = z.strictObject({
  job: z.string().max(200),
  name: z.string().min(1).max(200),
  organization: z.string().max(200),
})

const slotSchema = z.strictObject({
  backgroundColor: z.string().nullable(),
  height: z.number().nullable(),
  id: z.uuid(),
  placementStatus: z.enum(["UNPLACED", "PLACED"]),
  revision: z.number().int().nonnegative(),
  width: z.number().nullable(),
  x: z.number().nullable(),
  y: z.number().nullable(),
})

export const rosterEntrySchema = z.strictObject({
  id: z.uuid(),
  identity: rosterIdentitySchema,
  slot: slotSchema,
  submitted: z.boolean(),
})

const rosterSchema = z.array(rosterEntrySchema)
const rosterRowsSchema = z.strictObject({ rows: z.array(rosterIdentitySchema).max(50) })
const rosterErrorCodeSchema = z.enum([
  "BLANK_NAME",
  "DUPLICATE_IDENTITY",
  "FIELD_TOO_LONG",
  "FILE_TOO_LARGE",
  "INVALID_ENCODING",
  "INVALID_FIELDS",
  "INVALID_HEADER",
  "INVALID_JSON",
  "INVALID_MULTIPART",
  "INVALID_SHEET_COUNT",
  "INVALID_XLSX",
  "ROW_LIMIT",
  "UNSUPPORTED_FILE_TYPE",
])
const rosterImportErrorSchema = z.strictObject({
  code: z.literal("ROSTER_INVALID").optional(),
  errors: z.array(z.strictObject({
    code: rosterErrorCodeSchema,
    row: z.number().int().nonnegative().max(50),
  })).min(1).max(50),
})

export type RosterEntry = z.infer<typeof rosterEntrySchema>
export type RosterIdentity = z.infer<typeof rosterIdentitySchema>
export type RosterServerMarker = {
  readonly message: string
  readonly row: number
}

export class RosterImportRejectedError extends Error {
  readonly markers: readonly RosterServerMarker[]

  constructor(markers: readonly RosterServerMarker[]) {
    super("Roster import rejected")
    this.name = "RosterImportRejectedError"
    this.markers = markers
  }
}

export function describeRosterError(error: unknown): string {
  if (error instanceof RosterImportRejectedError) {
    const marker = error.markers.at(0)
    if (marker === undefined) return "명단 형식을 확인해 주세요."
    const location = marker.row === 0 ? "파일" : `${marker.row}행`
    return `${location}: ${marker.message}`
  }
  if (error instanceof ApiError) {
    const detail = rosterErrorCodeSchema.safeParse(error.details.at(0)?.code)
    if (detail.success) return safeRosterErrorMessage(detail.data)
  }
  return describeError(error)
}

export function rosterQueryKey(boardId: string) {
  return ["admin", "boards", boardId, "roster"] as const
}

export async function fetchRoster(boardId: string): Promise<readonly RosterEntry[]> {
  return rosterSchema.parse(await apiRequest(`/api/v1/admin/boards/${boardId}/roster`))
}

export async function createRosterEntry(boardId: string, identity: RosterIdentity): Promise<RosterEntry> {
  return rosterEntrySchema.parse(await apiRequest(`/api/v1/admin/boards/${boardId}/roster`, {
    body: JSON.stringify(rosterIdentitySchema.parse(identity)),
    headers: { "Content-Type": "application/json" },
    method: "POST",
  }))
}

export async function updateRosterEntry(
  boardId: string,
  entryId: string,
  identity: RosterIdentity,
): Promise<RosterEntry> {
  return rosterEntrySchema.parse(await apiRequest(`/api/v1/admin/boards/${boardId}/roster/${entryId}`, {
    body: JSON.stringify(rosterIdentitySchema.parse(identity)),
    headers: { "Content-Type": "application/json" },
    method: "PATCH",
  }))
}

export async function deleteRosterEntry(boardId: string, entryId: string): Promise<void> {
  await apiRequest(`/api/v1/admin/boards/${boardId}/roster/${entryId}`, { method: "DELETE" })
}

export async function replaceRoster(
  boardId: string,
  rows: readonly RosterIdentity[],
): Promise<readonly RosterEntry[]> {
  const body = rosterRowsSchema.parse({ rows })
  return rosterSchema.parse(await apiRequest(`/api/v1/admin/boards/${boardId}/roster`, {
    body: JSON.stringify(body),
    headers: { "Content-Type": "application/json" },
    method: "PUT",
  }))
}

export async function importRosterFile(boardId: string, file: File): Promise<readonly RosterEntry[]> {
  const body = new FormData()
  body.append("file", file)
  try {
    return rosterSchema.parse(await apiRequest(`/api/v1/admin/boards/${boardId}/roster/import`, {
      body,
      method: "POST",
    }))
  } catch (error) {
    if (error instanceof ApiError) {
      const rejected = rosterImportErrorSchema.safeParse({ errors: error.details })
      if (rejected.success) {
        const markers = rejected.data.errors
          .map((detail) => ({ message: safeRosterErrorMessage(detail.code), row: detail.row }))
          .sort((left, right) => left.row - right.row)
          .slice(0, 50)
        throw new RosterImportRejectedError(markers)
      }
    }
    throw error
  }
}

function safeRosterErrorMessage(code: z.infer<typeof rosterErrorCodeSchema>): string {
  switch (code) {
    case "BLANK_NAME":
      return "이름을 입력해 주세요."
    case "DUPLICATE_IDENTITY":
      return "같은 명단이 중복되었습니다."
    case "FIELD_TOO_LONG":
      return "입력한 값이 너무 깁니다."
    case "FILE_TOO_LARGE":
      return "파일 크기를 확인해 주세요."
    case "INVALID_ENCODING":
    case "INVALID_FIELDS":
    case "INVALID_HEADER":
    case "INVALID_JSON":
    case "INVALID_MULTIPART":
    case "INVALID_SHEET_COUNT":
    case "INVALID_XLSX":
      return "명단 형식을 확인해 주세요."
    case "ROW_LIMIT":
      return "명단은 50명까지 등록할 수 있습니다."
    case "UNSUPPORTED_FILE_TYPE":
      return "CSV 또는 XLSX 파일을 선택해 주세요."
  }
}
