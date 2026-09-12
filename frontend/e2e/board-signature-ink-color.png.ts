import { readFile } from "node:fs/promises";
import path from "node:path";
import type { Page } from "@playwright/test";

export async function decodeFinalPng(page: Page, pngPath: string) {
  const encoded = (await readFile(path.resolve(pngPath))).toString("base64");
  return page.evaluate(async (base64) => {
    const bytes = Uint8Array.from(atob(base64), (value) => value.charCodeAt(0));
    const bitmap = await createImageBitmap(
      new Blob([bytes], { type: "image/png" }),
    );
    const canvas = document.createElement("canvas");
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext("2d");
    if (context === null) throw new Error("Task 8 PNG decoder context missing");
    context.drawImage(bitmap, 0, 0);
    const data = context.getImageData(0, 0, canvas.width, canvas.height).data;
    const pixel = (x: number, y: number) => {
      const index = (y * canvas.width + x) * 4;
      return [data[index], data[index + 1], data[index + 2], data[index + 3]];
    };
    const strokeY = Math.round(canvas.height * 0.09);
    let whiteInterior = 0;
    for (let y = strokeY - 1; y <= strokeY + 1; y += 1) {
      for (
        let x = Math.round(canvas.width * 0.05);
        x <= Math.round(canvas.width * 0.19);
        x += 1
      ) {
        const value = pixel(x, y);
        if (
          value[0] === 255 &&
          value[1] === 255 &&
          value[2] === 255 &&
          value[3] === 255
        )
          whiteInterior += 1;
      }
    }
    let blankSlotChanged = 0;
    for (
      let y = Math.round(canvas.height * 0.03);
      y < Math.round(canvas.height * 0.15);
      y += 1
    ) {
      for (
        let x = Math.round(canvas.width * 0.27);
        x < Math.round(canvas.width * 0.45);
        x += 1
      ) {
        const value = pixel(x, y);
        if (
          value[0] !== 32 ||
          value[1] !== 32 ||
          value[2] !== 32 ||
          value[3] !== 255
        )
          blankSlotChanged += 1;
      }
    }
    return {
      background: pixel(
        Math.round(canvas.width * 0.8),
        Math.round(canvas.height * 0.8),
      ),
      blankSlotChanged,
      height: canvas.height,
      whiteInterior,
      width: canvas.width,
    };
  }, encoded);
}
