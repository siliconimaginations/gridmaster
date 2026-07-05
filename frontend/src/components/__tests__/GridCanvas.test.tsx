import { act, render } from '@testing-library/react'
import React from 'react'
import { describe, expect, it, vi } from 'vitest'

/**
 * Regression tests for #342 — GridCanvas renderer lifecycle serialisation.
 *
 * PIXI.Application.init() must never run twice concurrently on one canvas:
 * React StrictMode double-mounts effects in dev, and the un-serialised
 * implementation started a second async create while the first (or its
 * dispose) was still in flight, freezing the main thread on some machines.
 *
 * The mock tracks concurrency: `inFlight` counts creates that have started
 * but not resolved; `maxInFlight` must stay at 1.
 */
const stats = vi.hoisted(() => ({
  inFlight: 0,
  maxInFlight: 0,
  creates: 0,
  disposes: 0,
}))

vi.mock('../../renderer/PixiGridRenderer', () => ({
  PixiGridRenderer: {
    create: vi.fn(async () => {
      stats.creates++
      stats.inFlight++
      stats.maxInFlight = Math.max(stats.maxInFlight, stats.inFlight)
      // Simulate slow async WebGL init (the window where the race bites)
      await new Promise((resolve) => setTimeout(resolve, 20))
      stats.inFlight--
      return {
        dispose: () => {
          stats.disposes++
        },
        updateNetwork: vi.fn(),
        updateViolations: vi.fn(),
      }
    }),
  },
}))

import { GridCanvas } from '../GridCanvas'

describe('GridCanvas lifecycle serialisation (#342)', () => {
  it('skips the doomed first create under StrictMode double-mount', async () => {
    render(
      <React.StrictMode>
        <GridCanvas onSelect={() => undefined} />
      </React.StrictMode>,
    )

    // Let both mount cycles and the chained creates fully settle
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 150))
    })

    // Mount 1 is cleaned up before its chained create runs, so it is skipped
    // entirely; only mount 2 creates, and nothing overlaps.
    expect(stats.maxInFlight).toBe(1)
    expect(stats.creates).toBe(1)
    expect(stats.disposes).toBe(0)
  })

  it('never overlaps a create with a mid-flight unmount/remount cycle', async () => {
    const first = render(<GridCanvas onSelect={() => undefined} />)

    // Let the first create START (async init in flight) but not finish
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 5))
    })
    first.unmount()
    render(<GridCanvas onSelect={() => undefined} />)

    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 150))
    })

    // Second create must wait for the first create + its dispose to finish
    expect(stats.maxInFlight).toBe(1)
    // First create completed then was disposed (unmounted); second is live
    expect(stats.creates - stats.disposes).toBe(1)
    expect(stats.disposes).toBeGreaterThanOrEqual(1)
  })
})
