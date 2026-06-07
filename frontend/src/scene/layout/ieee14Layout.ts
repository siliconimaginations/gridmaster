/**
 * World-space (X, Z) positions for the IEEE 14-bus tutorial network.
 *
 * Coordinates are in world units (metres), within [-80, +80] in both axes.
 * Layout approximates the standard IEEE 14-bus single-line diagram.
 *
 * ⚠️  Bus ID keys below are provisional.
 * The actual IDs emitted by `IeeeCdfNetworkFactory.create14Solved()` have not been
 * verified against a live session. To confirm: start the game with the "ieee14"
 * preset, open the browser console, and check the first `GameStateUpdate.network.buses`
 * array. Update the keys here to match. Until confirmed, unmatched buses fall through
 * to `gridLayout`.
 *
 * Maintained client-side: the PowSyBl physics model carries no spatial coordinates
 * (see docs/engineering/14-scene-meshes.md §Design Decisions #1).
 */

/** World-space position for a single bus. Y is always 0 (ground level). */
export interface BusPosition {
  x: number
  z: number
}

/**
 * Bus ID → world position map for the IEEE 14-bus network.
 *
 * Bus numbers follow the IEEE 14-bus standard diagram:
 *   1 = slack generator (top-left)
 *   2,3,6,8 = PV generators
 *   4,5,7,9-14 = PQ load buses
 */
export const IEEE14_BUS_POSITIONS: Readonly<Record<string, BusPosition>> = {
  // PowSyBl IeeeCdfNetworkFactory typically names buses after their voltage level.
  // Common patterns: 'VH_1'/'VH1', or the IIDM bus-breaker view appends '_0'.
  // Update these keys after confirming from a live session (see TODO above).
  'VH_1':  { x: -60, z: -50 },
  'VH_2':  { x: -30, z: -50 },
  'VH_3':  { x:  10, z: -50 },
  'VH_4':  { x: -10, z: -20 },
  'VH_5':  { x: -40, z: -10 },
  'VH_6':  { x:  30, z: -30 },
  'VH_7':  { x:  20, z:  10 },
  'VH_8':  { x:  50, z:  10 },
  'VH_9':  { x:  20, z:  40 },
  'VH_10': { x:  40, z:  40 },
  'VH_11': { x:  55, z:  25 },
  'VH_12': { x:  65, z: -10 },
  'VH_13': { x:  65, z:  10 },
  'VH_14': { x:  35, z:  60 },
}

/** Returns true when `busId` has a confirmed IEEE 14-bus layout position. */
export function isIeee14Bus(busId: string): boolean {
  return busId in IEEE14_BUS_POSITIONS
}
