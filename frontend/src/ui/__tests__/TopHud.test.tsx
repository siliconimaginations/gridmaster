import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GridNetworkDto, ViolationDto } from '../../api/types'

// Mock the Zustand store — TopHud reads from it directly
vi.mock('../../state/useGameStore')
import { useGameStore } from '../../state/useGameStore'

import { TopHud } from '../TopHud'

/** Minimal network with one load at the specified MW. */
const makeNetwork = (loadMw: number): GridNetworkDto => ({
  buses: [], branches: [], generators: [],
  loads: [{ id: 'l1', busId: 'b1', name: 'Load1', activePowerMw: loadMw, reactivePowerMvar: 0 }],
})

const makeViolation = (value: number, limit: number): ViolationDto => ({
  elementId: 'e1', elementType: 'LINE', violationType: 'OVERLOAD', value, limit,
})

function mockStore(overrides: Record<string, unknown> = {}) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  vi.mocked(useGameStore).mockImplementation((selector?: (s: any) => any) => {
    const state = {
      gameTimeMinutes: 90,
      network: makeNetwork(500),
      violations: [],
      ...overrides,
    }
    return selector ? selector(state) : state
  })
}

describe('TopHud', () => {
  beforeEach(() => {
    mockStore()
  })

  it('renders all four pills', () => {
    render(<TopHud />)
    expect(screen.getByTestId('pill-clock')).toBeInTheDocument()
    expect(screen.getByTestId('pill-load')).toBeInTheDocument()
    expect(screen.getByTestId('pill-price')).toBeInTheDocument()
    expect(screen.getByTestId('pill-health')).toBeInTheDocument()
  })

  it('shows formatted game time', () => {
    render(<TopHud />)
    expect(screen.getByTestId('pill-clock')).toHaveTextContent('Day 1 · 01:30')
  })

  it('shows total load from network', () => {
    render(<TopHud />)
    expect(screen.getByTestId('pill-load')).toHaveTextContent('500 MW')
  })

  it('shows — /MWh for price (deferred)', () => {
    render(<TopHud />)
    expect(screen.getByTestId('pill-price')).toHaveTextContent('— /MWh')
  })

  it('health pill shows "Grid healthy" with no violations', () => {
    render(<TopHud />)
    const pill = screen.getByTestId('pill-health')
    expect(pill).toHaveTextContent('Grid healthy')
    expect(pill).toHaveAttribute('data-severity', 'ok')
  })

  it('health pill shows "N-1 risks" with minor violations', () => {
    mockStore({ violations: [makeViolation(105, 100)] })
    render(<TopHud />)
    const pill = screen.getByTestId('pill-health')
    expect(pill).toHaveTextContent('N-1 risks')
    expect(pill).toHaveAttribute('data-severity', 'warning')
  })

  it('health pill shows "Failure" with severe overload', () => {
    mockStore({ violations: [makeViolation(115, 100)] })
    render(<TopHud />)
    const pill = screen.getByTestId('pill-health')
    expect(pill).toHaveTextContent('Failure')
    expect(pill).toHaveAttribute('data-severity', 'critical')
  })

  it('load pill shows — MW when network is null', () => {
    mockStore({ network: null })
    render(<TopHud />)
    expect(screen.getByTestId('pill-load')).toHaveTextContent('— MW')
  })
})
