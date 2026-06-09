import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { EventCardDto } from '../../api/types'

vi.mock('../../state/useGameStore')
import { useGameStore } from '../../state/useGameStore'

import { EventCardPanel } from '../EventCardPanel'

// ── Fixtures ──────────────────────────────────────────────────────────────────

const OPTION_A = { id: 'opt-a', label: 'Shed industrial load', tag: 'DEMAND_REDUCTION', costGbp: 50000 }
const OPTION_B = { id: 'opt-b', label: 'Import from grid', tag: 'IMPORT', costGbp: 120000 }

const CARD_WARNING: EventCardDto = {
  id: 'card-1',
  title: 'Gas shortage',
  description: 'A gas supply disruption has reduced available generation capacity.',
  severity: 'WARNING',
  options: [OPTION_A, OPTION_B],
}

const CARD_CRITICAL: EventCardDto = {
  id: 'card-2',
  title: 'Transformer failure',
  description: 'The main transformer at substation 3 has tripped.',
  severity: 'CRITICAL',
  options: [OPTION_A],
}

// ── Mock helpers ──────────────────────────────────────────────────────────────

const mockSendCommand = vi.fn()

function mockStore(cards: EventCardDto[]) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  vi.mocked(useGameStore).mockImplementation((selector?: (s: any) => any) => {
    const state = { pendingEventCards: cards, sendCommand: mockSendCommand }
    return selector ? selector(state) : state
  })
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('EventCardPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders nothing when pendingEventCards is empty', () => {
    mockStore([])
    const { container } = render(<EventCardPanel />)
    expect(container.firstChild).toBeNull()
  })

  it('renders the overlay and panel when a card is present', () => {
    mockStore([CARD_WARNING])
    render(<EventCardPanel />)
    expect(screen.getByTestId('event-card-overlay')).toBeInTheDocument()
    expect(screen.getByTestId('event-card-panel')).toBeInTheDocument()
  })

  it('displays card title and description', () => {
    mockStore([CARD_WARNING])
    render(<EventCardPanel />)
    expect(screen.getByText(CARD_WARNING.title)).toBeInTheDocument()
    expect(screen.getByText(CARD_WARNING.description)).toBeInTheDocument()
  })

  it('renders all options with label, tag, and formatted cost', () => {
    mockStore([CARD_WARNING])
    render(<EventCardPanel />)
    expect(screen.getByTestId('event-option-opt-a')).toBeInTheDocument()
    expect(screen.getByTestId('event-option-opt-b')).toBeInTheDocument()
    expect(screen.getByText('Shed industrial load')).toBeInTheDocument()
    expect(screen.getByText('DEMAND_REDUCTION')).toBeInTheDocument()
    expect(screen.getByText('£50,000')).toBeInTheDocument()
    expect(screen.getByText('£120,000')).toBeInTheDocument()
  })

  it('Apply button is disabled until an option is selected', () => {
    mockStore([CARD_WARNING])
    render(<EventCardPanel />)
    const applyBtn = screen.getByTestId('event-card-apply')
    expect(applyBtn).toBeDisabled()
  })

  it('Apply button enables after selecting an option', () => {
    mockStore([CARD_WARNING])
    render(<EventCardPanel />)
    fireEvent.click(screen.getByTestId('event-option-opt-a'))
    expect(screen.getByTestId('event-card-apply')).not.toBeDisabled()
  })

  it('dispatches RespondToEventCard with correct cardId and optionId on Apply', () => {
    mockStore([CARD_WARNING])
    render(<EventCardPanel />)
    fireEvent.click(screen.getByTestId('event-option-opt-b'))
    fireEvent.click(screen.getByTestId('event-card-apply'))
    expect(mockSendCommand).toHaveBeenCalledOnce()
    expect(mockSendCommand).toHaveBeenCalledWith({
      commandType: 'RespondToEventCard',
      payload: { cardId: 'card-1', optionId: 'opt-b' },
    })
  })

  it('does not dispatch if Apply clicked with no selection', () => {
    mockStore([CARD_WARNING])
    render(<EventCardPanel />)
    fireEvent.click(screen.getByTestId('event-card-apply'))
    expect(mockSendCommand).not.toHaveBeenCalled()
  })

  it('shows only the first card when multiple are pending', () => {
    mockStore([CARD_WARNING, CARD_CRITICAL])
    render(<EventCardPanel />)
    expect(screen.getByText(CARD_WARNING.title)).toBeInTheDocument()
    expect(screen.queryByText(CARD_CRITICAL.title)).not.toBeInTheDocument()
  })

  it('shows severity label for CRITICAL card', () => {
    mockStore([CARD_CRITICAL])
    render(<EventCardPanel />)
    expect(screen.getByText('⚡ Critical Event')).toBeInTheDocument()
  })

  it('panel has dialog role for accessibility', () => {
    mockStore([CARD_WARNING])
    render(<EventCardPanel />)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})
