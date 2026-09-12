import { z } from "zod";
import { apiRequest, parsedApiRequest } from "../../../api/client.ts";
import { apiErrorFromResponse } from "../../../api/errors.ts";
import { type Board, boardSchema } from "../list/boardApi.ts";
import { type Bounds, normalizedBoundsSchema } from "./geometry.ts";

const slotResponseSchema = z.strictObject({
  boardId: z.uuid(),
  bounds: normalizedBoundsSchema,
  id: z.uuid(),
  revision: z.number().int().nonnegative(),
  rosterEntryId: z.uuid(),
  signaturePresent: z.boolean(),
  submitted: z.boolean(),
});
const shareSchema = z.strictObject({
  shareToken: z.string().min(1),
  version: z.number().int().positive(),
});
const backgroundSchema = z.strictObject({
  displayHeight: z.number().int().positive(),
  displayWidth: z.number().int().positive(),
  id: z.uuid(),
  mimeType: z.enum(["image/png", "image/jpeg"]),
});

export type SlotResponse = z.infer<typeof slotResponseSchema>;
export type Share = z.infer<typeof shareSchema>;
export type Background = z.infer<typeof backgroundSchema>;

export async function fetchBoardDetail(boardId: string): Promise<Board> {
  return parsedApiRequest(
    `/api/v1/admin/boards/${z.uuid().parse(boardId)}`,
    boardSchema,
  );
}

export async function fetchCurrentBackground(
  boardId: string,
): Promise<Blob | null> {
  const response = await fetch(
    `/api/v1/admin/boards/${z.uuid().parse(boardId)}/background`,
    {
      credentials: "same-origin",
    },
  );
  if (response.status === 204) return null;
  if (!response.ok) throw await apiErrorFromResponse(response);
  const mimeType = z
    .enum(["image/png", "image/jpeg"])
    .parse(response.headers.get("Content-Type")?.split(";").at(0));
  return new Blob([await response.arrayBuffer()], { type: mimeType });
}

const boardPatchSchema = z
  .strictObject({
    signatureInkColor: z.enum(["black", "white"]).optional(),
    title: z.string().min(1).optional(),
  })
  .refine(
    ({ signatureInkColor, title }) =>
      signatureInkColor !== undefined || title !== undefined,
  );

export type BoardPatch = z.infer<typeof boardPatchSchema>;

export async function renameBoard(
  boardId: string,
  patch: BoardPatch,
): Promise<Board> {
  return parsedApiRequest(
    `/api/v1/admin/boards/${z.uuid().parse(boardId)}`,
    boardSchema,
    {
      body: JSON.stringify(boardPatchSchema.parse(patch)),
      headers: { "Content-Type": "application/json" },
      method: "PATCH",
    },
  );
}

export async function saveSlot(
  boardId: string,
  slotId: string,
  bounds: Bounds,
): Promise<SlotResponse> {
  const body = storageBounds(bounds);
  return parsedApiRequest(
    `/api/v1/admin/boards/${z.uuid().parse(boardId)}/slots/${z.uuid().parse(slotId)}`,
    slotResponseSchema,
    {
      body: JSON.stringify(body),
      headers: { "Content-Type": "application/json" },
      method: "PATCH",
    },
  );
}

function storageBounds(bounds: Bounds): Bounds {
  const parsed = normalizedBoundsSchema.parse(bounds);
  const height = storageDecimal(parsed.height);
  const width = storageDecimal(parsed.width);
  return normalizedBoundsSchema.parse({
    height,
    width,
    x: Math.min(storageDecimal(parsed.x), storageDecimal(1 - width)),
    y: Math.min(storageDecimal(parsed.y), storageDecimal(1 - height)),
  });
}

function storageDecimal(value: number): number {
  return Number(value.toFixed(8));
}

export async function unplaceSlot(
  boardId: string,
  slotId: string,
): Promise<void> {
  await apiRequest(
    `/api/v1/admin/boards/${z.uuid().parse(boardId)}/slots/${z.uuid().parse(slotId)}`,
    { method: "DELETE" },
  );
}

export async function fetchShare(boardId: string): Promise<Share> {
  return parsedApiRequest(
    `/api/v1/admin/boards/${z.uuid().parse(boardId)}/share`,
    shareSchema,
  );
}

export async function reissueShare(boardId: string): Promise<Share> {
  return parsedApiRequest(
    `/api/v1/admin/boards/${z.uuid().parse(boardId)}/share/reissue`,
    shareSchema,
    { method: "POST" },
  );
}

export async function forceReplaceDisplay(boardId: string): Promise<void> {
  await apiRequest(
    `/api/v1/admin/boards/${z.uuid().parse(boardId)}/display/force-replace`,
    { method: "POST" },
  );
}

export async function uploadBackground(
  boardId: string,
  file: File,
  adoptSourceRatio: boolean,
  confirmed: boolean,
): Promise<Background> {
  const body = new FormData();
  body.append("file", file);
  body.append("adoptSourceRatio", String(adoptSourceRatio));
  body.append("confirmed", String(confirmed));
  return parsedApiRequest(
    `/api/v1/admin/boards/${z.uuid().parse(boardId)}/background`,
    backgroundSchema,
    { body, method: "POST" },
  );
}

export async function transitionBoard(
  boardId: string,
  action: "open" | "close" | "reopen",
): Promise<Board> {
  return parsedApiRequest(
    `/api/v1/admin/boards/${z.uuid().parse(boardId)}/${action}`,
    boardSchema,
    { method: "POST" },
  );
}
