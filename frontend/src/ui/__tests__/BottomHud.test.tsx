import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { EventCardDto } from '../../api/types'

vi.mock('../../state/useGameStore')
import { useGameStore } from '../../state/useGameStore'

import { BottomHud } from '../BottomHud'

const mockSendCommand = vi.fn()

function mockStore(overrides: Record<string, unknown> = {}) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  vi.mocked(useGameStore).mockImplementation((selector?: (s: any) => any) => {
    const state = {
      clockState: 'PAUSED',
      clockSpeedMultiplier: 1,
      sessionId: 'sess1',
      pendingEventCards: [],
      sendCommandOptimistic: mockSendCommand,
      ...overrides,
    }
    return selector ? selector(state) : state
  })
}

const makeEvent = (): EventCardDto => ({
  id: 'e1', title: 'Storm Warning', description: 'desc',
  severity: 'WARNING', options: [],
})

describe('BottomHud', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockStore()
  })

  it('renders play button when clock is PAUSED', () => {
    render(<BottomHud />)
    expect(screen.getByTestId('btn-play-pause')).toHaveTextContent('▶')
  })

  it('renders pause button when clock is RUNNING', () => {
    mockStore({ clockState: 'RUNNING' })
    render(<BottomHud />)
    expect(screen.getByTestId('btn-play-pause')).toHaveTextContent('⏸')
  })

  it('renders all four speed buttons', () => {
    render(<BottomHud />)
    expect(screen.getByTestId('btn-speed-1')).toBeInTheDocument()
    expect(screen.getByTestId('btn-speed-10')).toBeInTheDocument()
    expect(screen.getByTestId('btn-speed-60')).toBeInTheDocument()
    expect(screen.getByTestId('btn-speed-100')).toBeInTheDocument()
  })

  it('active speed button has aria-pressed=true', () => {
    mockStore({ clockSpeedMultiplier: 60 })
    render(<BottomHud />)
    expect(screen.getByTestId('btn-speed-60')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('btn-speed-1')).toHaveAttribute('aria-pressed', 'false')
  })

  it('clicking play dispatches ResumeClock when paused', async () => {
    render(<BottomHud />)
    await userEvent.click(screen.getByTestId('btn-play-pause'))
    expect(mockSendCommand).toHaveBeenCalledWith({ commandType: 'ResumeClock', payload: {} })
  })

  it('clicking pause dispatches PauseClock when running', async () => {
    mockStore({ clockState: 'RUNNING' })
    render(<BottomHud />)
    await userEvent.click(screen.getByTestId('btn-play-pause'))
    expect(mockSendCommand).toHaveBeenCalledWith({ commandType: 'PauseClock', payload: {} })
  })

  it('clicking a speed button dispatches SetClockSpeed', async () => {
    render(<BottomHud />)
    await userEvent.click(screen.getByTestId('btn-speed-60'))
    expect(mockSendCommand).toHaveBeenCalledWith({
      commandType: 'SetClockSpeed',
      payload: { multiplier: 60 },
    })
  })

  it('all buttons disabled when sessionId is null', () => {
    mockStore({ sessionId: null })
    render(<BottomHud />)
    expect(screen.getByTestId('btn-play-pause')).toBeDisabled()
    expect(screen.getByTestId('btn-speed-1')).toBeDisabled()
    expect(screen.getByTestId('btn-dispatch')).toBeDisabled()
  })

  it('event button not shown when no pending events', () => {
    render(<BottomHud />)
    expect(screen.queryByTestId('btn-event')).not.toBeInTheDocument()
  })

  it('shows event button with first event title when events pending', () => {
    mockStore({ pendingEventCards: [makeEvent()] })
    render(<BottomHud />)
    expect(screen.getByTestId('btn-event')).toHaveTextContent('Storm Warning')
  })
})
