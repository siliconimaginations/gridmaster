import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { PlanningPanel } from '../PlanningPanel'
import type { ViolationDto } from '../../api/types'

// ── Mock useGameStore ────────────────────────────────────────────────────────

vi.mock('../../state/useGameStore', () => {
  const mockStore: Record<string, unknown> = {}

  function useGameStore(selector: (s: typeof mockStore) => unknown) {
    return selector(mockStore)
  }

  useGameStore.__mockState = mockStore
  useGameStore.__reset = (state: Record<string, unknown>) => {
    Object.assign(mockStore, state)
  }

  return { useGameStore }
})

const { useGameStore } = await import('../../state/useGameStore') as any

function setStoreState(violations: ViolationDto[] | null = null) {
  useGameStore.__reset({ violations })
}

// ── Helpers ──────────────────────────────────────────────────────────────────

function renderPanel(open = true, onClose = vi.fn()) {
  return render(<PlanningPanel open={open} onClose={onClose} />)
}

function makeViolation(overrides: Partial<ViolationDto> = {}): ViolationDto {
  return {
    elementId: 'LINE-1',
    elementType: 'LINE',
    violationType: 'OVERLOAD',
    value: 120,
    limit: 100,
    ...overrides,
  }
}

// ── Tests ────────────────────────────────────────────────────────────────────

describe('PlanningPanel', () => {
  beforeEach(() => {
    setStoreState(null)
  })

  it('renders nothing when closed', () => {
    renderPanel(false)
    expect(screen.queryByTestId('planning-panel')).toBeNull()
  })

  it('renders panel when open', () => {
    renderPanel()
    expect(screen.getByTestId('planning-panel')).toBeTruthy()
  })

  it('shows Invest tab by default', () => {
    renderPanel()
    expect(screen.getByTestId('invest-tab')).toBeTruthy()
    expect(screen.queryByTestId('n1-table')).toBeNull()
    expect(screen.queryByTestId('forecast-tab')).toBeNull()
  })

  it('switches to N-1 Table tab on click', () => {
    renderPanel()
    fireEvent.click(screen.getByTestId('tab-n1'))
    expect(screen.queryByTestId('invest-tab')).toBeNull()
    expect(screen.getByTestId('n1-empty')).toBeTruthy()
  })

  it('switches to Forecast tab on click', () => {
    renderPanel()
    fireEvent.click(screen.getByTestId('tab-forecast'))
    expect(screen.queryByTestId('invest-tab')).toBeNull()
    expect(screen.getByTestId('forecast-tab')).toBeTruthy()
  })

  it('close button calls onClose', () => {
    const onClose = vi.fn()
    renderPanel(true, onClose)
    fireEvent.click(screen.getByTestId('btn-planning-close'))
    expect(onClose).toHaveBeenCalledOnce()
  })

  describe('Invest tab', () => {
    it('shows all 5 investment rows', () => {
      renderPanel()
      const rows = [
        screen.getByTestId('invest-row-solar-b'),
        screen.getByTestId('invest-row-ccgt-2'),
        screen.getByTestId('invest-row-wind-x'),
        screen.getByTestId('invest-row-battery-1'),
        screen.getByTestId('invest-row-line-l4'),
      ]
      expect(rows).toHaveLength(5)
    })

    it('shows budget amount', () => {
      renderPanel()
      expect(screen.getByText('£480M')).toBeTruthy()
    })
  })

  describe('N-1 Table tab', () => {
    it('shows empty state when no violations', () => {
      setStoreState(null)
      renderPanel()
      fireEvent.click(screen.getByTestId('tab-n1'))
      expect(screen.getByTestId('n1-empty')).toBeTruthy()
    })

    it('shows empty state when violations array is empty', () => {
      setStoreState([])
      renderPanel()
      fireEvent.click(screen.getByTestId('tab-n1'))
      expect(screen.getByTestId('n1-empty')).toBeTruthy()
    })

    it('renders a row for each violation', () => {
      setStoreState([
        makeViolation({ elementId: 'L1', violationType: 'OVERLOAD', value: 120, limit: 100 }),
        makeViolation({ elementId: 'B2', elementType: 'BUS', violationType: 'VOLTAGE_LOW', value: 0.89, limit: 1.0 }),
      ])
      renderPanel()
      fireEvent.click(screen.getByTestId('tab-n1'))
      expect(screen.getByTestId('n1-row-L1')).toBeTruthy()
      expect(screen.getByTestId('n1-row-B2')).toBeTruthy()
    })

    it('sorts rows by severity descending', () => {
      setStoreState([
        makeViolation({ elementId: 'MILD', value: 105, limit: 100 }),
        makeViolation({ elementId: 'SEVERE', value: 140, limit: 100 }),
      ])
      renderPanel()
      fireEvent.click(screen.getByTestId('tab-n1'))
      const rows = screen.getAllByTestId(/^n1-row-/)
      expect(rows[0].getAttribute('data-testid')).toBe('n1-row-SEVERE')
      expect(rows[1].getAttribute('data-testid')).toBe('n1-row-MILD')
    })
  })

  describe('Forecast tab', () => {
    it('renders 7 forecast bars', () => {
      renderPanel()
      fireEvent.click(screen.getByTestId('tab-forecast'))
      const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
      days.forEach((day) => {
        expect(screen.getByTestId(`forecast-bar-${day}`)).toBeTruthy()
      })
    })
  })
})
