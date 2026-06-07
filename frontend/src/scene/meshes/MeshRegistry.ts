/**
 * Owns and manages all grid element meshes in the scene.
 *
 * `updateNetwork(dto | null)` is the single entry point for scene state changes.
 * It is idempotent: calling it twice with the same network does not grow the mesh
 * count. Calling it with `null` disposes all element meshes.
 *
 * Ghost elements are removed: any mesh whose ID is absent from the new network
 * snapshot is disposed on each call.
 *
 * @see docs/engineering/14-scene-meshes.md §Element mesh registry
 */

import { Scene, Vector3 } from '@babylonjs/core'
import type { AbstractMesh, ParticleSystem } from '@babylonjs/core'
import type { GridNetworkDto, ViolationDto } from '../../api/types'
import { layoutBuses } from '../layout/busLayout'
import { createGeneratorMesh, generatorStatus, updateGeneratorStatus } from './generatorMesh'
import { createSubstationMesh } from './substationMesh'
import { createCityMesh, cityTier } from './cityMesh'
import { createLineMesh, lineColour } from './lineMesh'
import { createFlowParticles, resetDotTexture } from './particleFlow'

type GeneratorMeshes = ReturnType<typeof createGeneratorMesh>
type SubstationMeshes = ReturnType<typeof createSubstationMesh>
type LineMeshes = ReturnType<typeof createLineMesh>

// ── helpers ───────────────────────────────────────────────────────────────────

/** Disposes all meshes in an array. */
function disposeAll(meshes: AbstractMesh[]): void {
  for (const m of meshes) m.dispose()
}

/** Removes map entries whose keys are absent from `keepIds`, disposing their meshes. */
function pruneMap<T>(
  map: Map<string, T>,
  keepIds: Set<string>,
  disposeFn: (v: T) => void,
): void {
  for (const [id, value] of map) {
    if (!keepIds.has(id)) {
      disposeFn(value)
      map.delete(id)
    }
  }
}

// ── MeshRegistry ──────────────────────────────────────────────────────────────

export class MeshRegistry {
  private generators = new Map<string, GeneratorMeshes>()
  private substations = new Map<string, SubstationMeshes>()
  private cities = new Map<string, ReturnType<typeof createCityMesh>>()
  private lines = new Map<string, LineMeshes>()
  private particles = new Map<string, ParticleSystem>()

  constructor(private readonly scene: Scene) {}

  /** Recreates/updates all element meshes from the latest network snapshot. */
  updateNetwork(network: GridNetworkDto | null, violations: readonly ViolationDto[] = []): void {
    if (!network) { this.disposeAll(); return }

    const positions = layoutBuses(network.buses)

    // ── Remove ghost elements ────────────────────────────────────────────────
    pruneMap(this.generators, new Set(network.generators.map((g) => g.id)),
      ({ tower, ring }) => { tower.dispose(); ring.dispose() })
    pruneMap(this.substations, new Set(network.buses.map((b) => b.substationId).filter(Boolean) as string[]),
      ({ building, ring }) => { building.dispose(); ring.dispose() })
    pruneMap(this.cities, new Set(network.loads.map((l) => l.id)),
      (meshes) => disposeAll(meshes))
    pruneMap(this.lines, new Set(network.branches.map((b) => b.id)),
      ({ tube, pylons }) => { tube.dispose(); disposeAll(pylons) })
    pruneMap(this.particles, new Set(network.branches.map((b) => b.id)),
      (ps) => ps.dispose())

    // ── Generators ────────────────────────────────────────────────────────────
    for (const gen of network.generators) {
      const pos = positions.get(gen.busId) ?? { x: 0, z: 0 }
      const existing = this.generators.get(gen.id)
      if (existing) {
        updateGeneratorStatus(existing.ring, generatorStatus(gen, violations))
      } else {
        this.generators.set(gen.id, createGeneratorMesh(this.scene, new Vector3(pos.x, 0, pos.z), gen, violations))
      }
    }

    // ── Substations ───────────────────────────────────────────────────────────
    const seenSubs = new Set<string>()
    for (const bus of network.buses) {
      if (!bus.substationId || seenSubs.has(bus.substationId)) continue
      seenSubs.add(bus.substationId)
      if (!this.substations.has(bus.substationId)) {
        const pos = positions.get(bus.id) ?? { x: 0, z: 0 }
        this.substations.set(bus.substationId,
          createSubstationMesh(this.scene, new Vector3(pos.x, 0, pos.z), bus.substationId, violations))
      }
    }

    // ── Cities ────────────────────────────────────────────────────────────────
    for (const load of network.loads) {
      const pos = positions.get(load.busId) ?? { x: 0, z: 0 }
      const existing = this.cities.get(load.id)
      const newTier = cityTier(load.activePowerMw)
      const tierCounts: Record<string, number> = { village: 3, town: 5, city: 6 }
      if (existing) {
        if (existing.length !== tierCounts[newTier]) {
          disposeAll(existing)
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

      const existingLine = this.lines.get(branch.id)
      if (existingLine) {
        // Update line tube colour to reflect new loadingPercent / connected state
        const mat = existingLine.tube.material as { diffuseColor: unknown } | null
        if (mat) mat.diffuseColor = lineColour(branch)
      } else {
        this.lines.set(branch.id, createLineMesh(this.scene, from, to, branch))
      }

      // Recreate particle system if flow state changed
      if (!this.particles.has(branch.id)) {
        const ps = createFlowParticles(this.scene, from, to, branch)
        if (ps) { ps.start(); this.particles.set(branch.id, ps) }
      }
    }
  }

  /** Dispose all element meshes and particle systems. */
  disposeAll(): void {
    this.generators.forEach(({ tower, ring }) => { tower.dispose(); ring.dispose() })
    this.generators.clear()
    this.substations.forEach(({ building, ring }) => { building.dispose(); ring.dispose() })
    this.substations.clear()
    this.cities.forEach((ms) => disposeAll(ms))
    this.cities.clear()
    this.lines.forEach(({ tube, pylons }) => { tube.dispose(); disposeAll(pylons) })
    this.lines.clear()
    this.particles.forEach((ps) => ps.dispose())
    this.particles.clear()
    resetDotTexture()
  }
}
