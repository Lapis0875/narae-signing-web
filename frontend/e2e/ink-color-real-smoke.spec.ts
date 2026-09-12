import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { expect, test } from "@playwright/test";
import { readCredentials, requiredEnvironment } from "./realStackSupport.ts";

test.use({ extraHTTPHeaders: { "X-Narae-Client-IP": "127.0.0.1" } });

test("creates a synthetic board through the real nginx stack", async ({
  page,
}) => {
  // Given: the launcher supplied a real nginx URL and a mode-600 credential file.
  const baseUrl = new URL(requiredEnvironment("TASK8_BASE_URL"));
  const evidenceDir = path.resolve(requiredEnvironment("TASK8_EVIDENCE_DIR"));
  const { email, password } = readCredentials();
  await mkdir(evidenceDir, { recursive: true });

  // When: an administrator logs in and creates a synthetic board.
  await page.goto(new URL("/login", baseUrl).toString());
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(
    page.getByRole("heading", { level: 1, name: "보드 목록" }),
  ).toBeVisible();
  await page.getByRole("link", { name: "새 보드 만들기" }).click();
  await page.getByLabel("보드 제목").fill("잉크 색상 합성 QA 보드");
  await page.getByRole("button", { name: "보드 만들기" }).click();

  // Then: the nginx-backed editor is visible and durable evidence is written.
  await expect(page).toHaveURL(/\/boards\/[0-9a-f-]+\/edit$/u);
  await expect(
    page.getByRole("heading", { level: 1, name: "잉크 색상 합성 QA 보드" }),
  ).toBeVisible();
  await page.screenshot({
    fullPage: true,
    mask: [
      page.locator(".editor-share-url"),
      page.getByRole("img", { name: "현재 서명 링크 QR" }),
    ],
    path: path.join(evidenceDir, "smoke.png"),
  });
  await writeFile(
    path.join(evidenceDir, "smoke-result.json"),
    `${JSON.stringify(
      {
        browser: "chromium",
        executed: 1,
        nginxBacked: true,
        outcome: "passed",
        scenario: "login-and-create-synthetic-board",
        skipped: 0,
      },
      null,
      2,
    )}\n`,
    { encoding: "utf8", mode: 0o600 },
  );
});
