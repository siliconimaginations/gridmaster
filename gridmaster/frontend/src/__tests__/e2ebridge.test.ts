import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * Unit tests for e2ebridge.ts — the Playwright test bridge.
 */

// vi.hoisted ensures these are defined before the vi.mock factories run.
const { mockState, subscribers } = vi.hoisted(() => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const mockState: { network: string | null; selectElement: any } = {
    network: null,
    selectElement: vi.fn(),
  }
  const subscribers: Array<(s: typeof mockState) => void> = []
  return { mockState, subscribers }
})

vi.mock('react-dom', () => ({
  flushSync: vi.fn((fn: () => void) => fn()),
}))

vi.mock('../state/useGameStore', () => ({
  useGameStore: {
    getState: vi.fn(() => mockState),
    subscribe: vi.fn((cb: (s: typeof mockState) => void) => {
      subscribers.push(cb)
      return () => {
        const idx = subscribers.indexOf(cb)
        if (idx !== -1) subscribers.splice(idx, 1)
      }
    }),
  },
}))

import { installE2EBridge } from '../e2ebridge'

beforeEach(() => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  delete (window as any).__e2e
  subscribers.length = 0
  mockState.network = null
  mockState.selectElement.mockReset()
  installE2EBridge()
})

// ── installE2EBridge ──────────────────────────────────────────────────────────

describe('installE2EBridge', () => {
  it('attaches __e2e to window', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((window as any).__e2e).toBeDefined()
  })

  it('getStore returns the current store state', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((window as any).__e2e.getStore()).toBe(mockState)
  })

  it('executeSync invokes the action synchronously', () => {
    const fn = vi.fn()
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(window as any).__e2e.executeSync(fn)
    expect(fn).toHaveBeenCalled()
  })

  it('flushSelect calls selectElement with the provided info', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (window as any).__e2e.flushSelect(null)
    expect(mockState.selectElement).toHaveBeenCalledWith(null)
  })

  it('waitFor resolves immediately when predicate is already true', async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    await expect((window as any).__e2e.waitFor(() => true, 1000)).resolves.toBeUndefined()
  })

  it('waitFor resolves when a subscriber fires matching state', async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const promise = (window as any).__e2e.waitFor(
      (s: typeof mockState) => s.network === 'ready',
      5000,
    )
    mockState.network = 'ready'
    subscribers.forEach((cb) => cb(mockState))
    await expect(promise).resolves.toBeUndefined()
  })

  it('waitFor rejects when timeout elapses and subscriber fires', async () => {
    vi.useFakeTimers()
    try {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const promise = (window as any).__e2e.waitFor(
        () => false, // predicate never matches
        100,
      )
      // Advance Date.now past the deadline
      vi.advanceTimersByTime(200)
      // Trigger a subscriber to run the timeout check
      subscribers.forEach((cb) => cb(mockState))
      await expect(promise).rejects.toThrow('waitFor timeout')
    } finally {
      vi.useRealTimers()
    }
  })
})
