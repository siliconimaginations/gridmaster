/**
 * Shared E2E test configuration constants.
 *
 * Imported by test files that need direct backend access rather than
 * going through the Vite dev-server proxy.  Centralised here so the
 * URL is defined exactly once and can be overridden via the BACKEND_URL
 * environment variable in CI.
 */

/** Backend API base URL — bypasses Vite proxy to avoid auth-header stripping. */
export const BACKEND_URL = process.env['BACKEND_URL'] ?? 'http://localhost:8080'
