/**
 * Deterministic grid layout for networks whose buses are not in a hardcoded
 * layout map (e.g. IEEE 14-bus layout during development, or future custom networks).
 *
 * Buses are placed in a square grid, row-major, with `GRID_SPACING` world units
 * between adjacent buses. The grid is centred at the origin.
 *
 * This layout is intentionally simple — it produces a readable result for any
 * network without requiring coordinate metadata from the backend.
 */

import type { BusDto } from '../../api/types'
import type { BusPosition } from './ieee14Layout'

const GRID_SPACING = 20  // world units between adjacent buses

/**
 * Assigns grid positions to `buses` in index order.
 * Returns a map of `busId → {x, z}`.
 */
export function gridLayout(buses: readonly BusDto[]): Map<string, BusPosition> {
  const positions = new Map<string, BusPosition>()
  if (buses.length === 0) return positions

  const cols = Math.ceil(Math.sqrt(buses.length))
  const totalWidth = (cols - 1) * GRID_SPACING
  const totalDepth = (Math.ceil(buses.length / cols) - 1) * GRID_SPACING

  buses.forEach((bus, index) => {
    const col = index % cols
    const row = Math.floor(index / cols)
    positions.set(bus.id, {
      x: col * GRID_SPACING - totalWidth / 2,
      z: row * GRID_SPACING - totalDepth / 2,
    })
  })

  return positions
}
