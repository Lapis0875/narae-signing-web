import { expect, type Page } from "@playwright/test";
import { z } from "zod";
import { primaryIdentity, secondaryIdentity } from "./realStackSupport.ts";

const boardSchema = z.object({
  canvasHeight: z.number().int().positive(),
  canvasWidth: z.number().int().positive(),
  id: z.uuid(),
  signatureInkColor: z.enum(["black", "white"]),
  status: z.enum(["설정 중", "서명 진행", "마감/보관"]),
});
const errorSchema = z.object({ code: z.string().min(1) });
const signatureSchema = z.object({
  strokes: z.array(z.object({ points: z.array(z.unknown()).min(1) })).min(1),
});
const displaySnapshotSchema = z.object({
  boardId: z.uuid(),
  signatureInkColor: z.enum(["black", "white"]),
  slots: z.array(
    z.object({
      draftSignature: signatureSchema.nullable(),
      id: z.uuid(),
      signature: signatureSchema.nullable(),
    }),
  ),
});

export type BoardState = z.infer<typeof boardSchema>;
export type JsonResult = {
  readonly body: unknown;
  readonly status: number;
};
export type BackgroundFixture = {
  readonly buffer: Buffer;
  readonly height: 180;
  readonly width: 320;
};

export async function login(
  page: Page,
  baseUrl: URL,
  email: string,
  password: string,
): Promise<void> {
  await page.goto(new URL("/login", baseUrl).toString());
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(
    page.getByRole("heading", { level: 1, name: "보드 목록" }),
  ).toBeVisible();
}

export async function createDraftBoard(
  page: Page,
  title: string,
): Promise<string> {
  await page.getByRole("link", { name: "새 보드 만들기" }).click();
  await page.getByLabel("보드 제목").fill(title);
  await page.getByRole("button", { name: "보드 만들기" }).click();
  await expect(page).toHaveURL(/\/boards\/[0-9a-f-]+\/edit$/u);
  const boardId = /^\/boards\/([0-9a-f-]+)\/edit$/u.exec(
    new URL(page.url()).pathname,
  )?.[1];
  if (boardId === undefined) throw new Error("Task 8 board id missing");
  return boardId;
}

export async function addPlacedRoster(page: Page): Promise<void> {
  const editor = page.getByRole("region", { name: "명단 편집" });
  for (const identity of [primaryIdentity, secondaryIdentity]) {
    await editor.locator("#new-organization").fill(identity.organization);
    await editor.locator("#new-job").fill(identity.job);
    await editor.locator("#new-name").fill(identity.name);
    await editor.getByRole("button", { name: "명단 추가" }).click();
    await page.getByRole("button", { name: `${identity.name} 배치` }).click();
    await expect(page.getByLabel("자동 저장 상태")).toHaveText("저장됨");
  }
}

export async function darkBackground(page: Page): Promise<BackgroundFixture> {
  const encoded = await page.evaluate(() => {
    const canvas = document.createElement("canvas");
    canvas.width = 320;
    canvas.height = 180;
    const context = canvas.getContext("2d");
    if (context === null) throw new Error("Task 8 canvas context missing");
    context.fillStyle = "rgb(32,32,32)";
    context.fillRect(0, 0, canvas.width, canvas.height);
    return canvas.toDataURL("image/png").split(",").at(1) ?? "";
  });
  return { buffer: Buffer.from(encoded, "base64"), height: 180, width: 320 };
}

export async function uploadBackground(
  page: Page,
  boardId: string,
  buffer: Buffer,
): Promise<void> {
  await page.getByLabel("PNG 또는 JPEG 파일 선택").setInputFiles({
    buffer,
    mimeType: "image/png",
    name: "dark-background.png",
  });
  const uploaded = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname ===
        `/api/v1/admin/boards/${boardId}/background` &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "이미지 비율 적용" }).click();
  await page.getByRole("button", { exact: true, name: "비율 적용" }).click();
  expect((await uploaded).status()).toBe(200);
  await expect(page.locator("img.editor-canvas-background")).toBeVisible();
}

export async function adminJson(
  page: Page,
  pathName: string,
  method: "GET" | "PATCH" | "POST",
  body?: string,
): Promise<JsonResult> {
  return page.evaluate(
    async ({
      body: requestBody,
      method: requestMethod,
      pathName: requestPath,
    }) => {
      const headers = new Headers();
      if (requestMethod !== "GET") {
        const token = document.cookie
          .split(";")
          .map((cookie) => cookie.trim())
          .find((cookie) => cookie.startsWith("XSRF-TOKEN="))
          ?.slice("XSRF-TOKEN=".length);
        if (token === undefined) throw new Error("Task 8 CSRF cookie missing");
        headers.set("X-XSRF-TOKEN", token);
      }
      if (requestBody !== undefined)
        headers.set("Content-Type", "application/json");
      const response = await fetch(requestPath, {
        body: requestBody,
        credentials: "same-origin",
        headers,
        method: requestMethod,
      });
      return { body: await response.json(), status: response.status };
    },
    { body, method, pathName },
  );
}

export function board(value: unknown): BoardState {
  return boardSchema.parse(value);
}

export function errorCode(value: unknown): string {
  return errorSchema.parse(value).code;
}

export function displaySnapshot(value: unknown) {
  return displaySnapshotSchema.parse(value);
}
