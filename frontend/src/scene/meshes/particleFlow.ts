/**
 * Animated "flowing dash" overlay for transmission power flow. (#278)
 *
 * Replaces the original ParticleSystem approach (which had rendering issues)
 * with a thin tube mesh riding over the visible line tube. The tube's material
 * uses a procedural dash texture whose `uOffset` is scrolled each frame via a
 * scene observer, making the dashes appear to travel from `from` → `to` (or
 * reversed for negative flow). Speed is proportional to |activePowerMw|.
 *
 * Public API intentionally mirrors the ParticleSystem API used by MeshRegistry:
 *   createFlowParticles(scene, from, to, dto) → FlowDash | null
 *   updateFlowParticles(dash, from, to, dto)  → boolean
 *   resetDotTexture()
 *
 * @see docs/engineering/14-scene-meshes.md §Power flow particles
 * @see issue #278
 */

import { DynamicTexture, MeshBuilder, Scene, StandardMaterial, Vector3 } from '@babylonjs/core'
import type { BranchDto } from '../../api/types'

// ── Constants ─────────────────────────────────────────────────────────────────

/** Radius of the dash overlay tube — sits between TUBE_RADIUS (0.40) and HIT_RADIUS (1.20). */
const FLOW_TUBE_RADIUS = 0.58
/** Minimum scroll speed so even low-flow lines show motion. */
const SCROLL_SPEED_BASE = 0.25   // UV units / second
/** Additional scroll speed per MW of active power. */
const SCROLL_SPEED_PER_MW = 0.003

// ── Shared dash texture ───────────────────────────────────────────────────────

let _dashTexture: DynamicTexture | null = null

/**
 * Returns a lazily-created 256×8 px DynamicTexture with a white dash on a
 * black background (60 % white, 40 % black). The texture wraps horizontally so
 * scrolling `uOffset` produces a repeating dash animation.
 */
function getDashTexture(scene: Scene): DynamicTexture {
  if (!_dashTexture) {
    const W = 256
    const H = 8
    _dashTexture = new DynamicTexture('flow_dash_tex', { width: W, height: H }, scene, false)
    const ctx = _dashTexture.getContext()
    ctx.fillStyle = '#000000'
    ctx.fillRect(0, 0, W, H)
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, Math.floor(W * 0.6), H)
    _dashTexture.update()
    _dashTexture.wrapU = 1   // WRAP_ADDRESSMODE
    _dashTexture.wrapV = 1
  }
  return _dashTexture
}

/** Resets the shared texture cache (call when the scene is disposed). */
export function resetDotTexture(): void {
  _dashTexture = null
}

// ── FlowDash ──────────────────────────────────────────────────────────────────

/**
 * An animated dash overlay for one transmission line.
 *
 * MeshRegistry treats this exactly like a `ParticleSystem` — it calls
 * `start()` once after creation and `dispose()` when the line disappears.
 */
export class FlowDash {
  private readonly mesh: ReturnType<typeof MeshBuilder.CreateTube>
  private readonly mat: StandardMaterial
  private uOffset = 0
  private speed: number
  private direction: 1 | -1
  private renderCallback: (() => void) | null = null

  constructor(
    private readonly scene: Scene,
    from: Vector3,
    to: Vector3,
    dto: BranchDto,
  ) {
    const path = [from.clone(), to.clone()]
    this.mesh = MeshBuilder.CreateTube(`flow_dash_${dto.id}`, { path, radius: FLOW_TUBE_RADIUS }, scene)
    this.mesh.isPickable = false

    this.mat = new StandardMaterial(`flow_dash_mat_${dto.id}`, scene)
    this.mat.emissiveTexture = getDashTexture(scene)
    this.mat.disableLighting = true
    this.mat.backFaceCulling = false
    this.mesh.material = this.mat

    this.speed = SCROLL_SPEED_BASE + Math.abs(dto.activePowerMw) * SCROLL_SPEED_PER_MW
    this.direction = dto.activePowerMw >= 0 ? 1 : -1
  }

  /** Registers the per-frame UV scroll observer. */
  start(): void {
    let last = performance.now()
    this.renderCallback = () => {
      const now = performance.now()
      const dt = Math.min((now - last) / 1000, 0.1)   // cap dt at 100 ms
      last = now
      this.uOffset = ((this.uOffset + this.speed * this.direction * dt) % 1 + 1) % 1
      if (this.mat.emissiveTexture) {
        this.mat.emissiveTexture.uOffset = this.uOffset
      }
    }
    this.scene.onBeforeRenderObservable.add(this.renderCallback)
  }

  /**
   * Updates flow speed and direction from new branch data.
   * Returns `false` if the branch is now disconnected or zero-flow (caller should dispose).
   */
  update(_from: Vector3, _to: Vector3, dto: BranchDto): boolean {
    if (!dto.connected || dto.activePowerMw === 0) return false
    this.speed = SCROLL_SPEED_BASE + Math.abs(dto.activePowerMw) * SCROLL_SPEED_PER_MW
    this.direction = dto.activePowerMw >= 0 ? 1 : -1
    return true
  }

  /** Removes the observer and disposes all GPU resources. */
  dispose(): void {
    if (this.renderCallback) {
      this.scene.onBeforeRenderObservable.removeCallback(this.renderCallback)
      this.renderCallback = null
    }
    this.mesh.dispose()
    this.mat.dispose()
  }
}

// ── Module-level API (mirrors old particleFlow.ts surface) ───────────────────

/**
 * Creates a `FlowDash` for an active line.
 * Returns `null` for disconnected or zero-flow branches.
 * Caller must call `.start()` before the dash will animate.
 */
export function createFlowParticles(
  scene: Scene,
  from: Vector3,
  to: Vector3,
  dto: BranchDto,
): FlowDash | null {
  if (!dto.connected || dto.activePowerMw === 0) return null
  return new FlowDash(scene, from, to, dto)
}

/**
 * Updates an existing `FlowDash` in-place.
 * Returns `false` if the branch is now zero/disconnected and the dash
 * should be disposed by the caller.
 */
export function updateFlowParticles(
  dash: FlowDash,
  from: Vector3,
  to: Vector3,
  dto: BranchDto,
): boolean {
  return dash.update(from, to, dto)
}
