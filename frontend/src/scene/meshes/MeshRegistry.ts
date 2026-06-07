/**
 * Owns and manages all grid element meshes in the scene.
 *
 * `updateNetwork(dto | null)` is the single entry point for scene state changes.
 * It is idempotent — calling it twice with the same network does not grow the
 * mesh count. Calling it with `null` disposes all element meshes.
 *
 * @see docs/engineering/14-scene-meshes.md §Element mesh registry
 */

import { Scene, Vector3 } from '@babylonjs/core'
import type { GridNetworkDto, ViolationDto } from '../../api/types'
import { layoutBuses } from '../layout/busLayout'
import { createGeneratorMesh, generatorStatus, updateGeneratorStatus } from './generatorMesh'
import { createSubstationMesh } from './substationMesh'
import { createCityMesh, cityTier } from './cityMesh'
import { createLineMesh } from './lineMesh'
import { createFlowParticles, resetDotTexture } from './particleFlow'
import type { ParticleSystem } from '@babylonjs/core'

type GeneratorMeshes = ReturnType<typeof createGeneratorMesh>
type LineMeshes = ReturnType<typeof createLineMesh>

export class MeshRegistry {
  private generators = new Map<string, GeneratorMeshes>()
  private substations = new Map<string, { building: ReturnType<typeof Scene.prototype.getMeshByName>; ring: ReturnType<typeof Scene.prototype.getMeshByName> }>()
  private cities = new Map<string, ReturnType<typeof createCityMesh>>()
  private lines = new Map<string, LineMeshes>()
  private particles = new Map<string, ParticleSystem>()

  constructor(private readonly scene: Scene) {}

  /** Recreates/updates all element meshes from the latest network snapshot. */
  updateNetwork(network: GridNetworkDto | null, violations: readonly ViolationDto[] = []): void {
    if (!network) { this.disposeAll(); return }

    const positions = layoutBuses(network.buses)

    // ── Generators ────────────────────────────────────────────────────────────
    for (const gen of network.generators) {
      const pos = positions.get(gen.busId) ?? { x: 0, z: 0 }
      const worldPos = new Vector3(pos.x, 0, pos.z)
      const existing = this.generators.get(gen.id)
      if (existing) {
        updateGeneratorStatus(existing.ring, generatorStatus(gen, violations))
      } else {
        const meshes = createGeneratorMesh(this.scene, worldPos, gen, violations)
        this.generators.set(gen.id, meshes)
      }
    }

    // ── Substations ───────────────────────────────────────────────────────────
    const seen = new Set<string>()
    for (const bus of network.buses) {
      if (!bus.substationId || seen.has(bus.substationId)) continue
      seen.add(bus.substationId)
      const pos = positions.get(bus.id) ?? { x: 0, z: 0 }
      if (!this.substations.has(bus.substationId)) {
        const meshes = createSubstationMesh(this.scene, new Vector3(pos.x, 0, pos.z), bus.substationId, violations)
        this.substations.set(bus.substationId, meshes as never)
      }
    }

    // ── Cities ────────────────────────────────────────────────────────────────
    for (const load of network.loads) {
      const pos = positions.get(load.busId) ?? { x: 0, z: 0 }
      const existing = this.cities.get(load.id)
      const newTier = cityTier(load.activePowerMw)
      if (existing) {
        const oldCount = existing.length
        // Rebuild if tier changed (check via mesh count as proxy)
        const expectedCounts: Record<string, number> = { village: 3, town: 5, city: 6 }
        if (oldCount !== expectedCounts[newTier]) {
          existing.forEach((m) => m.dispose())
          this.cities.set(load.id, createCityMesh(this.scene, new Vector3(pos.x, 0, pos.z), load))
        }
      } else {
        this.cities.set(load.id, createCityMesh(this.scene, new Vector3(pos.x, 0, pos.z), load))
      }
    }

    // ── Lines + particles ─────────────────────────────────────────────────────
    for (const branch of network.branches) {
      const fromPos = positions.get(branch.fromBusId)
      const toPos = positions.get(branch.toBusId)
      if (!fromPos || !toPos) continue
      const from = new Vector3(fromPos.x, 4, fromPos.z)
      const to = new Vector3(toPos.x, 4, toPos.z)

      if (!this.lines.has(branch.id)) {
        this.lines.set(branch.id, createLineMesh(this.scene, from, to, branch))
        const ps = createFlowParticles(this.scene, from, to, branch)
        if (ps) { ps.start(); this.particles.set(branch.id, ps) }
      }
    }
  }

  /** Dispose all element meshes and particle systems. */
  disposeAll(): void {
    this.generators.forEach(({ tower, ring }) => { tower.dispose(); ring.dispose() })
    this.generators.clear()
    this.substations.forEach((s) => { (s as unknown as { building: { dispose(): void }; ring: { dispose(): void } }).building?.dispose(); (s as unknown as { building: { dispose(): void }; ring: { dispose(): void } }).ring?.dispose() })
    this.substations.clear()
    this.cities.forEach((ms) => ms.forEach((m) => m.dispose()))
    this.cities.clear()
    this.lines.forEach(({ tube, pylons }) => { tube.dispose(); pylons.forEach((p) => p.dispose()) })
    this.lines.clear()
    this.particles.forEach((ps) => ps.dispose())
    this.particles.clear()
    resetDotTexture()
  }
}
