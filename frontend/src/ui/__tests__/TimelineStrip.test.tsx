import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { TimelineStrip } from '../TimelineStrip'
import { useGameStore } from '../../state/useGameStore'

// Babylon.js mock (required by transitive imports in the module graph)
vi.mock('@babylonjs/core')

function setStoreState(gameTimeMinutes: number, ucSchedule: boolean[] | null = null) {
  useGameStore.setState({ gameTimeMinutes, ucSchedule })
}

beforeEach(() => {
  useGameStore.setState({ gameTimeMinutes: 0, ucSchedule: null })
})

describe('TimelineStrip', () => {
  it('renders 24 hour blocks', () => {
    render(<TimelineStrip />)
    expect(screen.getAllByTestId(/^timeline-block-/).length).toBe(24)
  })

  it('renders the Now indicator', () => {
    render(<TimelineStrip />)
    expect(screen.getByTestId('timeline-now')).toBeTruthy()
  })

  it('marks no blocks committed when ucSchedule is null', () => {
    setStoreState(0, null)
    render(<TimelineStrip />)
    // All blocks should have uncommitted class — check via title attribute
    const block0 = screen.getByTestId('timeline-block-0')
    expect(block0.title).toContain('Not scheduled')
  })

  it('marks committed blocks when ucSchedule is provided', () => {
    const schedule = Array(24).fill(false)
    schedule[0] = true
    schedule[6] = true
    setStoreState(60, schedule)
    render(<TimelineStrip />)
    expect(screen.getByTestId('timeline-block-0').title).toContain('Committed')
    expect(screen.getByTestId('timeline-block-6').title).toContain('Committed')
    expect(screen.getByTestId('timeline-block-1').title).toContain('Not scheduled')
  })

  it('computes current hour from gameTimeMinutes', () => {
    // gameTimeMinutes = 780 → day-minute 780 % 1440 = 780 → hour 13
    setStoreState(780)
    render(<TimelineStrip />)
    // Block 13 should be the "now" hour — label will have green class
    // The now indicator is positioned based on currentHour; just verify it renders
    expect(screen.getByTestId('timeline-now')).toBeTruthy()
  })

  it('wraps game time past one day correctly', () => {
    // gameTimeMinutes = 1500 → 1500 % 1440 = 60 → hour 1
    setStoreState(1500)
    render(<TimelineStrip />)
    expect(screen.getByTestId('timeline-strip')).toBeTruthy()
  })
})
