/**
 * Procedural mesh for a city / load element.
 *
 * Building count and height tier is derived from `activePowerMw`.
 * Meshes are rebuilt when the tier changes (infrequent; demand grows over game-days).
 *
 * @see docs/engineering/14-scene-meshes.md §City/Load mesh
 */

import { Color3, MeshBuilder, Scene, Vector3 } from '@babylonjs/core'
import { createToonMaterial } from '../materials/ToonMaterial'
import type { LoadDto } from '../../api/types'

export type CityTier = 'village' | 'town' | 'city'

const BUILDING_COLOUR = new Color3(0.99, 0.95, 0.76)   // warm beige

/** Derives city tier from total load in MW. */
export function cityTier(activePowerMw: number): CityTier {
  if (activePowerMw < 100) return 'village'
  if (activePowerMw <= 500) return 'town'
  return 'city'
}

const TIER_CONFIG: Record<CityTier, { count: number; heights: number[] }> = {
  village: { count: 3, heights: [1.5, 1.2, 1.8] },
  town:    { count: 5, heights: [2.5, 2.0, 3.0, 2.2, 1.8] },
  city:    { count: 6, heights: [5.0, 4.0, 6.0, 3.5, 4.5, 5.5] },
}

const BUILDING_SPACING = 2.5  // world units between buildings

/**
 * Creates a cluster of box buildings for a load element.
 * Position is offset slightly from the bus centre (+3 on X) to avoid z-fighting.
 * Returns the array of building meshes (needed for disposal on tier change).
 */
export function createCityMesh(scene: Scene, position: Vector3, dto: LoadDto): ReturnType<typeof MeshBuilder.CreateBox>[] {
  const tier = cityTier(dto.activePowerMw)
  const { count, heights } = TIER_CONFIG[tier]
  const origin = position.clone()
  origin.x += 3

  return Array.from({ length: count }, (_, i) => {
    const h = heights[i]
    const mesh = MeshBuilder.CreateBox(`city_${dto.id}_${i}`, { width: 1.5, height: h, depth: 1.5 }, scene)
    mesh.position = origin.clone()
    mesh.position.x += (i % 3) * BUILDING_SPACING
    mesh.position.z += Math.floor(i / 3) * BUILDING_SPACING
    mesh.position.y = h / 2
    mesh.material = createToonMaterial(scene, BUILDING_COLOUR, `city_mat_${dto.id}_${i}`)
    return mesh
  })
}
