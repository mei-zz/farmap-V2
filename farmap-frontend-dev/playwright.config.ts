import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 300_000,
  expect: { timeout: 30_000 },
  reporter: [["list"], ["json", { outputFile: "test-results/commercial-pilot.json" }]],
  use: {
    baseURL: process.env.FARMAP_FRONTEND_URL || "http://127.0.0.1:5173/farmap/",
    browserName: "chromium",
    headless: true,
    launchOptions: {
      executablePath: process.env.FARMAP_CHROMIUM_EXECUTABLE,
      args: [
        "--disable-gpu",
        "--disable-software-rasterizer",
        "--disable-extensions",
        "--disable-background-networking",
      ],
    },
    ...devices["Desktop Chrome"],
    video: "off",
    trace: "off",
  },
});
