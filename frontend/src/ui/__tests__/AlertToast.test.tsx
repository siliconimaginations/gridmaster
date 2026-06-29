import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AlertDto } from '../../api/types'

vi.mock('../../state/useGameStore')
import { useGameStore } from '../../state/useGameStore'

import { AlertToastContainer } from '../AlertToast'

// ── Helpers ───────────────────────────────────────────────────────────────────

function makeAlert(overrides: Partial<AlertDto> = {}): AlertDto {
  return {
    id: 'a1',
    severity: 'INFO',
    message: 'Test alert',
    elementId: null,
    timestampMs: Date.now(),
    acknowledged: false,
    ...overrides,
  }
}

const dismissLocalAlertMock = vi.fn()

function mockAlerts(alerts: AlertDto[], localAlerts: AlertDto[] = []) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  vi.mocked(useGameStore).mockImplementation((selector?: (s: any) => any) => {
    const state = { alerts, localAlerts, dismissLocalAlert: dismissLocalAlertMock }
    return selector ? selector(state) : state
  })
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('AlertToastContainer', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    mockAlerts([])
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders nothing when there are no alerts', () => {
    const { container } = render(<AlertToastContainer />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders a toast for each alert (up to MAX_VISIBLE=3)', () => {
    mockAlerts([
      makeAlert({ id: 'a1', message: 'Alert 1' }),
      makeAlert({ id: 'a2', message: 'Alert 2' }),
      makeAlert({ id: 'a3', message: 'Alert 3' }),
    ])
    render(<AlertToastContainer />)
    expect(screen.getByTestId('toast-a1')).toBeInTheDocument()
    expect(screen.getByTestId('toast-a2')).toBeInTheDocument()
    expect(screen.getByTestId('toast-a3')).toBeInTheDocument()
  })

  it('shows at most 3 toasts even when more alerts exist', () => {
    mockAlerts([
      makeAlert({ id: 'a1' }),
      makeAlert({ id: 'a2' }),
      makeAlert({ id: 'a3' }),
      makeAlert({ id: 'a4' }),
    ])
    render(<AlertToastContainer />)
    expect(screen.queryByTestId('toast-a4')).not.toBeInTheDocument()
    expect(screen.getAllByTestId(/^toast-/)).toHaveLength(3)
  })

  it('shows the severity emoji', () => {
    mockAlerts([
      makeAlert({ id: 'a1', severity: 'CRITICAL', message: 'Overload' }),
    ])
    render(<AlertToastContainer />)
    expect(screen.getByTestId('toast-a1')).toHaveTextContent('⚡')
  })

  it('removes a toast when the dismiss button is clicked', () => {
    mockAlerts([makeAlert({ id: 'a1', message: 'Dismiss me' })])
    render(<AlertToastContainer />)

    expect(screen.getByTestId('toast-a1')).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('Dismiss: Dismiss me'))
    expect(screen.queryByTestId('toast-a1')).not.toBeInTheDocument()
  })

  it('auto-dismisses INFO toasts after 5s', () => {
    mockAlerts([makeAlert({ id: 'a1', severity: 'INFO' })])
    render(<AlertToastContainer />)

    expect(screen.getByTestId('toast-a1')).toBeInTheDocument()
    act(() => { vi.advanceTimersByTime(5000) })
    expect(screen.queryByTestId('toast-a1')).not.toBeInTheDocument()
  })

  it('auto-dismisses WARNING toasts after 8s', () => {
    mockAlerts([makeAlert({ id: 'a1', severity: 'WARNING' })])
    render(<AlertToastContainer />)

    act(() => { vi.advanceTimersByTime(7999) })
    expect(screen.getByTestId('toast-a1')).toBeInTheDocument()
    act(() => { vi.advanceTimersByTime(1) })
    expect(screen.queryByTestId('toast-a1')).not.toBeInTheDocument()
  })

  it('never auto-dismisses CRITICAL toasts', () => {
    mockAlerts([makeAlert({ id: 'a1', severity: 'CRITICAL' })])
    render(<AlertToastContainer />)

    act(() => { vi.advanceTimersByTime(60_000) })
    expect(screen.getByTestId('toast-a1')).toBeInTheDocument()
  })

  it('de-duplicates alerts with the same elementId, keeping the newest', () => {
    mockAlerts([
      makeAlert({ id: 'older', elementId: 'line-L5', message: 'Old overload' }),
      makeAlert({ id: 'newer', elementId: 'line-L5', message: 'New overload' }),
    ])
    render(<AlertToastContainer />)

    expect(screen.queryByTestId('toast-older')).not.toBeInTheDocument()
    expect(screen.getByTestId('toast-newer')).toBeInTheDocument()
  })

  it('does not de-duplicate alerts with elementId === null', () => {
    mockAlerts([
      makeAlert({ id: 'a1', elementId: null, message: 'Global 1' }),
      makeAlert({ id: 'a2', elementId: null, message: 'Global 2' }),
    ])
    render(<AlertToastContainer />)

    expect(screen.getByTestId('toast-a1')).toBeInTheDocument()
    expect(screen.getByTestId('toast-a2')).toBeInTheDocument()
  })

  it('renders the toast container with the correct test ID when alerts are present', () => {
    mockAlerts([makeAlert({ id: 'a1' })])
    render(<AlertToastContainer />)
    expect(screen.getByTestId('alert-toast-container')).toBeInTheDocument()
  })
})
