/**
 * Selects the appropriate bus layout strategy and returns a `Map<busId, BusPosition>`.
 *
 * Strategy:
 * 1. Try the IEEE 14-bus hardcoded map for each bus.
 * 2. For buses not in the map, fall back to the grid layout.
 *
 * In practice, once bus IDs are confirmed, all 14 buses will resolve via the
 * hardcoded map. During development, or for custom networks, the grid fallback
 * provides a usable layout without any configuration.
 */

import type { BusDto } from '../../api/types'
import { IEEE14_BUS_POSITIONS, isIeee14Bus } from './ieee14Layout'
import { gridLayout } from './gridLayout'

export type { BusPosition } from './ieee14Layout'

/**
 * Returns world-space `{x, z}` positions for all buses.
 *
 * Buses with known IEEE 14-bus IDs use the hardcoded layout; the remainder
 * use the deterministic grid fallback.
 */
export function layoutBuses(buses: readonly BusDto[]): Map<string, { x: number; z: number }> {
  const unmatched: BusDto[] = []
  const hardcodedEntries: Array<[string, { x: number; z: number }]> = []

  for (const bus of buses) {
    const hardcoded = IEEE14_BUS_POSITIONS[bus.id]
    if (hardcoded) {
      hardcodedEntries.push([bus.id, hardcoded])
    } else {
      unmatched.push(bus)
    }
  }

  const positions = gridLayout(unmatched)
  for (const [id, pos] of hardcodedEntries) positions.set(id, pos)

  return positions
}
