import { defineConfig, devices } from "@playwright/test";

class InkColorRealConfigurationError extends Error {
  constructor(name: string) {
    super(`Ink-color real QA configuration error: ${name}`);
    this.name = "InkColorRealConfigurationError";
  }
}

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (value === undefined || value.length === 0) {
    throw new InkColorRealConfigurationError(name);
  }
  return value;
}

if (requiredEnvironment("TASK8_REAL_MODE") !== "real") {
  throw new InkColorRealConfigurationError("TASK8_REAL_MODE must equal real");
}

const baseURL = requiredEnvironment("TASK8_BASE_URL");
requiredEnvironment("TASK8_CREDENTIAL_FILE");
const evidenceDir = requiredEnvironment("TASK8_EVIDENCE_DIR");
const suiteMode = requiredEnvironment("TASK8_SUITE_MODE");
const testMatch = (() => {
  switch (suiteMode) {
    case "full":
      return /(?:board-signature-ink-color|mobile-slot-label|public-display-real-stack)\.spec\.ts/u;
    case "public-baseline":
      return /public-display-real-stack\.spec\.ts/u;
    case "smoke":
      return /ink-color-real-smoke\.spec\.ts/u;
    default:
      throw new InkColorRealConfigurationError(
        "TASK8_SUITE_MODE must equal full, public-baseline, or smoke",
      );
  }
})();

export default defineConfig({
  forbidOnly: true,
  outputDir: `${evidenceDir}/playwright-output`,
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  reporter: "line",
  testDir: ".",
  testMatch,
  timeout: 90_000,
  use: {
    baseURL,
    extraHTTPHeaders: { "X-Narae-Client-IP": "127.0.0.1" },
    screenshot: "off",
    trace: "off",
    video: "off",
  },
  workers: 1,
});
