import { describe, expect, it } from 'vitest'
import { DEFAULT_RADIUS, ISO_ALPHA, ISO_BETA, ZOOM_MAX, ZOOM_MIN } from '../camera'

/**
 * Unit tests for the isometric camera constants and configuration.
 *
 * The Babylon.js engine requires a real WebGL context, so we test the
 * exported constants only. Integration against a live scene is covered
 * by the manual smoke-test checklist in the design doc.
 */
describe('createIsometricCamera — constants', () => {
  it('alpha is locked at -45° (Math.PI / 4)', () => {
    expect(ISO_ALPHA).toBeCloseTo(-Math.PI / 4)
  })

  it('beta is locked at Math.PI / 5 (≈36° from zenith)', () => {
    expect(ISO_BETA).toBeCloseTo(Math.PI / 5)
  })

  it('zoom minimum is positive and less than default radius', () => {
    expect(ZOOM_MIN).toBeGreaterThan(0)
    expect(ZOOM_MIN).toBeLessThan(DEFAULT_RADIUS)
  })

  it('zoom maximum is greater than default radius', () => {
    expect(ZOOM_MAX).toBeGreaterThan(DEFAULT_RADIUS)
  })

  it('default radius sits between zoom bounds', () => {
    expect(DEFAULT_RADIUS).toBeGreaterThan(ZOOM_MIN)
    expect(DEFAULT_RADIUS).toBeLessThan(ZOOM_MAX)
  })
})
