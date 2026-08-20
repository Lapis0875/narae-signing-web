import { defineConfig, devices } from "@playwright/test"

export default defineConfig({
  outputDir: process.env.PLAYWRIGHT_OUTPUT_DIR ?? "test-results",
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
    { name: "webkit", use: { ...devices["Desktop Safari"] } },
  ],
  testDir: "./e2e",
  use: {
    baseURL: "http://127.0.0.1:4173",
    screenshot: "on",
    trace: "on",
  },
  webServer: {
    command: "npm run dev -- --host 127.0.0.1 --port 4173",
    reuseExistingServer: false,
    url: "http://127.0.0.1:4173",
  },
})
