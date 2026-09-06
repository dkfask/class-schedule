import { defineConfig } from '@playwright/test';

// 前置条件（套件不自动拉起环境）：
//   docker compose up -d postgres api worker
//   npm run dev（frontend，:5173）
// 默认使用本机 Chrome（channel: chrome）；如需内置浏览器：
//   npx playwright install chromium && PLAYWRIGHT_CHANNEL=chromium npm run e2e
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    channel: process.env.PLAYWRIGHT_CHANNEL ?? 'chrome',
    viewport: { width: 1280, height: 720 },
    screenshot: 'only-on-failure',
  },
});
