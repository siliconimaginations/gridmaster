import { describe, expect, it } from 'vitest'
import type { GridNetworkDto, ViolationDto } from '../../api/types'
import { formatGameTime, gridHealthStatus, totalLoadMw } from '../hud'

// ── formatGameTime ────────────────────────────────────────────────────────────

describe('formatGameTime', () => {
  it('formats minute 0 as Day 1 · 00:00', () => {
    expect(formatGameTime(0)).toBe('Day 1 · 00:00')
  })

  it('formats 90 minutes as Day 1 · 01:30', () => {
    expect(formatGameTime(90)).toBe('Day 1 · 01:30')
  })

  it('formats exactly 1440 minutes (24 h) as Day 2 · 00:00', () => {
    expect(formatGameTime(1440)).toBe('Day 2 · 00:00')
  })

  it('formats 1441 minutes as Day 2 · 00:01', () => {
    expect(formatGameTime(1441)).toBe('Day 2 · 00:01')
  })

  it('pads single-digit hours and minutes with leading zero', () => {
    expect(formatGameTime(65)).toBe('Day 1 · 01:05')
  })
})

// ── totalLoadMw ───────────────────────────────────────────────────────────────

const makeNetwork = (loadsMw: number[]): GridNetworkDto => ({
  buses: [],
  branches: [],
  generators: [],
  loads: loadsMw.map((mw, i) => ({
    id: `l${i}`,
    busId: 'b1',
    name: `Load${i}`,
    activePowerMw: mw,
    reactivePowerMvar: 0,
  })),
})

describe('totalLoadMw', () => {
  it('returns "— MW" when network is null', () => {
    expect(totalLoadMw(null)).toBe('— MW')
  })

  it('returns "0 MW" for a network with no loads', () => {
    expect(totalLoadMw(makeNetwork([]))).toBe('0 MW')
  })

  it('sums all load active powers', () => {
    expect(totalLoadMw(makeNetwork([100, 200, 531.4]))).toBe('831 MW')
  })
})

// ── gridHealthStatus ──────────────────────────────────────────────────────────

const makeViolation = (type: ViolationDto['violationType'], value: number, limit: number): ViolationDto => ({
  elementId: 'e1',
  elementType: 'LINE',
  violationType: type,
  value,
  limit,
})

describe('gridHealthStatus', () => {
  it('returns healthy with no violations', () => {
    const result = gridHealthStatus([])
    expect(result.label).toBe('Grid healthy')
    expect(result.severity).toBe('ok')
  })

  it('returns N-1 risks for a minor overload (not exceeding 110% of limit)', () => {
    const result = gridHealthStatus([makeViolation('OVERLOAD', 105, 100)])
    expect(result.label).toBe('N-1 risks')
    expect(result.severity).toBe('warning')
  })

  it('returns Failure for a severe overload (>110% of limit)', () => {
    const result = gridHealthStatus([makeViolation('OVERLOAD', 115, 100)])
    expect(result.label).toBe('Failure')
    expect(result.severity).toBe('critical')
  })

  it('returns N-1 risks for a voltage violation (not critical threshold)', () => {
    const result = gridHealthStatus([makeViolation('VOLTAGE_HIGH', 1.1, 1.05)])
    expect(result.label).toBe('N-1 risks')
    expect(result.severity).toBe('warning')
  })
})
