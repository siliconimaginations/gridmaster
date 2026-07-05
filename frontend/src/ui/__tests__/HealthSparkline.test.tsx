import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { HealthSparkline } from '../HealthSparkline'

describe('HealthSparkline (#333)', () => {
  it('renders nothing with fewer than 3 samples', () => {
    const { container } = render(<HealthSparkline history={[80, 70]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders an svg polyline once 3 samples exist', () => {
    render(<HealthSparkline history={[80, 70, 60]} />)
    expect(screen.getByTestId('hud-health-sparkline')).toBeInTheDocument()
    const line = screen.getByTestId('hud-health-sparkline-line')
    // 3 samples → 3 "x,y" pairs
    expect(line.getAttribute('points')!.split(' ')).toHaveLength(3)
  })

  it('is green when the last value is ≥ 60', () => {
    render(<HealthSparkline history={[20, 40, 75]} />)
    expect(screen.getByTestId('hud-health-sparkline-line')).toHaveAttribute('stroke', '#34d399')
  })

  it('is amber when the last value is 30-59', () => {
    render(<HealthSparkline history={[80, 70, 45]} />)
    expect(screen.getByTestId('hud-health-sparkline-line')).toHaveAttribute('stroke', '#fbbf24')
  })

  it('is red when the last value is below 30', () => {
    render(<HealthSparkline history={[80, 50, 10]} />)
    expect(screen.getByTestId('hud-health-sparkline-line')).toHaveAttribute('stroke', '#f87171')
  })

  it('clamps out-of-range samples into the viewBox', () => {
    render(<HealthSparkline history={[-20, 150, 50]} />)
    const points = screen.getByTestId('hud-health-sparkline-line').getAttribute('points')!
    for (const pair of points.split(' ')) {
      const [x, y] = pair.split(',').map(Number)
      expect(x).toBeGreaterThanOrEqual(0)
      expect(x).toBeLessThanOrEqual(60)
      expect(y).toBeGreaterThanOrEqual(0)
      expect(y).toBeLessThanOrEqual(20)
    }
  })
})
