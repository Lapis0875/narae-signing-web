import { z } from "zod"
import { apiRequest } from "../../../api/client.ts"

export const boardSchema = z.strictObject({
  canvasHeight: z.number().int().positive(),
  canvasWidth: z.number().int().positive(),
  createdAt: z.iso.datetime(),
  id: z.uuid(),
  shareLinkVersion: z.number().int().positive(),
  status: z.enum(["설정 중", "서명 진행", "마감/보관"]),
  title: z.string().min(1),
  updatedAt: z.iso.datetime(),
})

const boardListSchema = z.array(boardSchema)
const createdBoardSchema = z.strictObject({
  board: boardSchema,
  shareToken: z.string().min(1),
})

export type Board = z.infer<typeof boardSchema>

export const boardListQueryKey = ["admin", "boards"] as const

export async function fetchBoards(): Promise<readonly Board[]> {
  return boardListSchema.parse(await apiRequest("/api/v1/admin/boards"))
}

export async function fetchBoard(boardId: string): Promise<Board> {
  return boardSchema.parse(await apiRequest(`/api/v1/admin/boards/${boardId}`))
}

export async function createBoard(title: string): Promise<Board> {
  const created = createdBoardSchema.parse(await apiRequest("/api/v1/admin/boards", {
    body: JSON.stringify({ title }),
    headers: { "Content-Type": "application/json" },
    method: "POST",
  }))
  return created.board
}
