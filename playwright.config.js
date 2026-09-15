import { defineConfig } from '@playwright/test';
export default defineConfig({ testDir: './web/vue/test', use: { baseURL: 'http://127.0.0.1:4179', headless: true }, webServer: { command: 'npx vite --config examples/web/vite.config.js --host 127.0.0.1 --port 4179', url: 'http://127.0.0.1:4179/examples/web/', reuseExistingServer: false } });
