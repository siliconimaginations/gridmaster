/**
 * Power flow particle animation for transmission lines.
 *
 * Creates a Babylon ParticleSystem that emits glowing dots along
 * the line path. Speed and emit rate are proportional to `activePowerMw`.
 * Direction reverses when `activePowerMw < 0`.
 *
 * Uses a DynamicTexture (procedural white circle) — no external asset required.
 *
 * @see docs/engineering/14-scene-meshes.md §Power flow particles
 */

import { Color4, DynamicTexture, ParticleSystem, Scene, Vector3 } from '@babylonjs/core'
import type { BranchDto } from '../../api/types'

const PARTICLE_SIZE   = 0.4
const BASE_SPEED      = 0.1   // world units/frame at 60 fps
const MW_PER_PARTICLE = 50    // 1 particle/s per 50 MW

/** Creates (or returns cached) glow-dot texture for all particle systems. */
let _dotTexture: DynamicTexture | null = null
function getDotTexture(scene: Scene): DynamicTexture {
  if (!_dotTexture) {
    _dotTexture = new DynamicTexture('glow_dot', { width: 16, height: 16 }, scene)
    const ctx = _dotTexture.getContext()
    ctx.clearRect(0, 0, 16, 16)
    ctx.fillStyle = '#ffffff'
    ctx.beginPath()
    ctx.arc(8, 8, 7, 0, Math.PI * 2)
    ctx.fill()
    _dotTexture.update()
  }
  return _dotTexture
}

/**
 * Creates a `ParticleSystem` that sends particles from `from` toward `to`.
 * Returns the system (must be started by caller).
 * Returns `null` for disconnected or zero-flow lines.
 */
export function createFlowParticles(scene: Scene, from: Vector3, to: Vector3, dto: BranchDto): ParticleSystem | null {
  if (!dto.connected || dto.activePowerMw === 0) return null

  const direction = to.subtract(from).normalize()
  const speed = Math.abs(dto.activePowerMw) / 200 + BASE_SPEED
  const flowDir = dto.activePowerMw >= 0 ? direction : direction.negate()

  const ps = new ParticleSystem(`flow_${dto.id}`, 50, scene)
  ps.particleTexture = getDotTexture(scene)
  ps.emitter = from.clone() as unknown as Vector3
  ps.minSize = ps.maxSize = PARTICLE_SIZE
  ps.emitRate = Math.max(1, Math.abs(dto.activePowerMw) / MW_PER_PARTICLE)
  ps.minLifeTime = ps.maxLifeTime = Vector3.Distance(from, to) / speed / 60
  ps.minEmitPower = ps.maxEmitPower = speed
  ps.direction1 = ps.direction2 = flowDir
  ps.color1 = new Color4(1, 0.98, 0.76, 1)   // soft yellow
  ps.color2 = ps.colorDead = new Color4(1, 0.98, 0.76, 0)

  return ps
}

/** Resets the cached dot texture (call when scene is disposed). */
export function resetDotTexture(): void {
  _dotTexture = null
}
