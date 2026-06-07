import { afterEach, expect } from 'vitest'
import { cleanup } from '@testing-library/react'
import * as matchers from '@testing-library/jest-dom/matchers'

// Extend Vitest's expect with jest-dom matchers (toBeInTheDocument, etc.)
expect.extend(matchers)

// React Testing Library auto-cleanup after each test.
// Explicitly called here because Vitest does not run afterEach globals
// unless the test file imports them — the setup file bridges this gap.
afterEach(cleanup)
