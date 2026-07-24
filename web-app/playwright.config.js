import { defineConfig, devices } from '@playwright/test'

const webOrigin = 'http://127.0.0.1:4173'
const apiOrigin = 'http://127.0.0.1:18080'

export default defineConfig({
  testDir: './tests/e2e',
  outputDir: './test-results',
  reporter: [['list']],
  use: {
    ...devices['Pixel 7'],
    browserName: 'firefox',
    baseURL: webOrigin,
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: [
    {
      command: 'node tests/e2e/fake-api.mjs',
      url: `${apiOrigin}/health`,
      reuseExistingServer: false,
      timeout: 30_000,
    },
    {
      command: 'npm run dev -- --host 127.0.0.1 --port 4173',
      url: webOrigin,
      reuseExistingServer: false,
      timeout: 60_000,
      env: {
        VITE_API_URL: apiOrigin,
      },
    },
  ],
})
