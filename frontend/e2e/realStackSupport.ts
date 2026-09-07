import { readFileSync } from "node:fs";
import { expect, type Locator, type Page } from "@playwright/test";
import { z } from "zod";

export const primaryIdentity = {
  job: "검증 A",
  name: "시험 서명자 A",
  organization: "나래 QA",
} as const;
export const secondaryIdentity = {
  job: "검증 B",
  name: "시험 서명자 B",
  organization: "나래 QA",
} as const;

const credentialsSchema = z.strictObject({
  email: z.email(),
  password: z.string().min(15),
});

const draftEventSchema = z
  .strictObject({
    draftEpoch: z.number().int().nonnegative(),
    revision: z.number().int().nonnegative(),
    slotId: z.uuid(),
  })
  .passthrough();

export class RealStackConfigurationError extends Error {
  constructor(detail: string) {
    super(`Task 8 real stack configuration error: ${detail}`);
    this.name = "RealStackConfigurationError";
  }
}

export function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (value === undefined || value.length === 0)
    throw new RealStackConfigurationError(name);
  return value;
}

export function readCredentials(): z.infer<typeof credentialsSchema> {
  return credentialsSchema.parse(
    JSON.parse(
      readFileSync(requiredEnvironment("TASK8_CREDENTIAL_FILE"), "utf8"),
    ),
  );
}

export async function createOpenBoard(
  page: Page,
  baseUrl: URL,
  email: string,
  password: string,
) {
  await page.goto(new URL("/login", baseUrl).toString());
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(
    page.getByRole("heading", { level: 1, name: "보드 목록" }),
  ).toBeVisible();
  await page.getByRole("link", { name: "새 보드 만들기" }).click();
  await page.getByLabel("보드 제목").fill("Task 8 합성 검증");
  await page.getByRole("button", { name: "보드 만들기" }).click();

  const rosterEditor = page.getByRole("region", { name: "명단 편집" });
  for (const identity of [primaryIdentity, secondaryIdentity]) {
    await rosterEditor.locator("#new-organization").fill(identity.organization);
    await rosterEditor.locator("#new-job").fill(identity.job);
    await rosterEditor.locator("#new-name").fill(identity.name);
    await rosterEditor.getByRole("button", { name: "명단 추가" }).click();
    await page.getByRole("button", { name: `${identity.name} 배치` }).click();
    await expect(page.getByLabel("자동 저장 상태")).toHaveText("저장됨");
  }
  await page.getByRole("button", { name: "서명 시작" }).click();
  await expect(page.getByText("서명 진행", { exact: true })).toBeVisible();

  const shareUrl = await page
    .getByRole("img", { name: "현재 서명 링크 QR" })
    .getAttribute("data-share-url");
  const boardId = /^\/boards\/([0-9a-f-]+)\/edit$/u.exec(
    new URL(page.url()).pathname,
  )?.[1];
  if (shareUrl === null || boardId === undefined)
    throw new RealStackConfigurationError("created board URL");
  return { boardId, shareUrl };
}

export async function identify(
  page: Page,
  shareUrl: string,
  identity: typeof primaryIdentity | typeof secondaryIdentity,
) {
  await page.goto(shareUrl);
  await expect(page.getByTestId("public-signer-identify")).toBeVisible();
  await page.getByLabel("소속사 (선택)").fill(identity.organization);
  await page.getByLabel("직책 (선택)").fill(identity.job);
  await page.getByLabel("이름").fill(identity.name);
  const response = page.waitForResponse((candidate) =>
    new URL(candidate.url()).pathname.endsWith("/identify"),
  );
  await page.getByRole("button", { name: "정보 확인" }).click();
  return response;
}

export async function drawStroke(page: Page, yFraction: number) {
  const canvas = page.getByTestId("signer-canvas");
  const box = await canvas.boundingBox();
  if (box === null)
    throw new RealStackConfigurationError("signer canvas bounds");
  await page.mouse.move(
    box.x + box.width * 0.2,
    box.y + box.height * yFraction,
  );
  await page.mouse.down();
  await page.mouse.move(
    box.x + box.width * 0.8,
    box.y + box.height * yFraction,
    { steps: 5 },
  );
  await page.mouse.up();
}

export async function canvasData(canvas: Locator): Promise<string> {
  return canvas.evaluate((element) =>
    element instanceof HTMLCanvasElement ? element.toDataURL() : "",
  );
}

export async function blackPixelCount(canvas: Locator): Promise<number> {
  return canvas.evaluate((element) => {
    if (!(element instanceof HTMLCanvasElement)) return 0;
    const context = element.getContext("2d");
    if (context === null || context.strokeStyle !== "#000000") return 0;
    const pixels = context.getImageData(
      0,
      0,
      element.width,
      element.height,
    ).data;
    let count = 0;
    for (let index = 0; index < pixels.length; index += 4) {
      if (
        pixels[index] === 0 &&
        pixels[index + 1] === 0 &&
        pixels[index + 2] === 0 &&
        (pixels[index + 3] ?? 0) > 0
      )
        count += 1;
    }
    return count;
  });
}

export async function startDraftEventCapture(
  page: Page,
  shareToken: string,
): Promise<void> {
  await page.evaluate((token) => {
    document.documentElement.dataset.task8DraftEvents = "";
    const source = new EventSource(
      `/api/v1/public/links/${encodeURIComponent(token)}/display/events`,
    );
    source.addEventListener("signature-draft", (event) => {
      if (event instanceof MessageEvent && typeof event.data === "string") {
        document.documentElement.dataset.task8DraftEvents += `${event.data}\n`;
      }
    });
  }, shareToken);
}

export async function capturedDraftEvents(page: Page) {
  const raw =
    (await page.locator("html").getAttribute("data-task8-draft-events")) ?? "";
  return raw.trim().length === 0
    ? []
    : raw
        .trim()
        .split("\n")
        .map((line) => draftEventSchema.parse(JSON.parse(line)));
}

export function parseDraftEventPayloads(payloads: readonly string[]) {
  return payloads.map((payload) => draftEventSchema.parse(JSON.parse(payload)));
}
