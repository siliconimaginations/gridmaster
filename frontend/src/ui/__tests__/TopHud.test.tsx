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

/** Network with one committed generator at the given output/cost, for production-cost tests. */
const makeNetworkWithGenerator = (
  loadMw: number,
  activePowerMw: number,
  marginalCostPerMwh: number,
  committed = true,
): GridNetworkDto => ({
  ...makeNetwork(loadMw),
  generators: [
    {
      id: 'g1', busId: 'b1', name: 'Gen1', fuelType: 'GAS',
      activePowerMw, setpointMw: activePowerMw, maxActivePowerMw: 500, committed, marginalCostPerMwh,
      dispatchable: true,
    },
  ],
})

const makeViolation = (value: number, limit: number): ViolationDto => ({
  elementId: 'e1', elementType: 'LINE', violationType: 'OVERLOAD', value, limit,
})

function mockStore(overrides: Record<string, unknown> = {}) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  vi.mocked(useGameStore).mockImplementation((selector?: (s: any) => any) => {
    const state = {
      gameTimeMinutes: 90,
      tickNumber: 0,
      clockState: 'RUNNING',
      network: makeNetwork(500),
      violations: [],
      healthScore: null,
      healthHistory: [],
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
    expect(screen.getByTestId('pill-production-cost')).toBeInTheDocument()
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

  it('shows the daily load curve multiplier when present (#383)', () => {
    mockStore({ dailyLoadMultiplier: 1.184 })
    render(<TopHud />)
    expect(screen.getByTestId('hud-daily-load-multiplier')).toHaveTextContent('×1.18')
  })

  it('omits the daily load curve multiplier when null (#383)', () => {
    mockStore({ dailyLoadMultiplier: null })
    render(<TopHud />)
    expect(screen.queryByTestId('hud-daily-load-multiplier')).toBeNull()
  })

  it('shows — /h when there are no generators (#377)', () => {
    render(<TopHud />)
    expect(screen.getByTestId('hud-production-cost')).toHaveTextContent('— /h')
  })

  it('shows total production cost as Σ (output MW × marginal cost) across committed generators (#377)', () => {
    // 200 MW × £48.6/MWh = £9,720/h
    mockStore({ network: makeNetworkWithGenerator(500, 200, 48.6) })
    render(<TopHud />)
    expect(screen.getByTestId('hud-production-cost')).toHaveTextContent('£9,720/h')
  })

  it('excludes decommitted generators from the production cost total (#377)', () => {
    mockStore({ network: makeNetworkWithGenerator(500, 200, 48.6, false) })
    render(<TopHud />)
    expect(screen.getByTestId('hud-production-cost')).toHaveTextContent('— /h')
  })

  it('no longer shows the old systemMarginalCostPerMwh-based price ticker (#377)', () => {
    mockStore({ network: { ...makeNetwork(500), systemMarginalCostPerMwh: 99.4 } })
    render(<TopHud />)
    expect(screen.queryByTestId('pill-price')).toBeNull()
    expect(screen.queryByTestId('hud-price')).toBeNull()
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

  it('trend arrows are rendered for load and health pills (#274)', () => {
    render(<TopHud />)
    expect(screen.getByTestId('hud-load-trend')).toBeInTheDocument()
    expect(screen.getByTestId('hud-health-trend')).toBeInTheDocument()
  })

  it('load and health trend arrows show — on first render (insufficient history)', () => {
    render(<TopHud />)
    // tickNumber=0 is skipped by the effect; history is empty → flat
    expect(screen.getByTestId('hud-load-trend')).toHaveTextContent('—')
    expect(screen.getByTestId('hud-health-trend')).toHaveTextContent('—')
  })
})
