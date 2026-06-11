import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  define: {
    // sockjs-client references Node's `global`; polyfill it for browser builds.
    global: 'globalThis',
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
  test: {
    exclude: ['node_modules/**', 'e2e/**'],
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    coverage: {
      provider: 'v8',
      // Reporters: text summary in CI logs + JSON files for the PR comment action.
      reporter: ['text', 'json', 'json-summary'],
      reportsDirectory: './coverage',
      // Exclude generated/config files from metrics.
      exclude: [
        'node_modules/**',
        'dist/**',
        '**/*.config.*',
        '**/*.d.ts',
        'src/main.tsx',        // entry point — no logic
        'src/vite-env.d.ts',
      ],
    },
  },
})
