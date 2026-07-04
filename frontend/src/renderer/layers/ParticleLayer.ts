/**
 * ParticleLayer — GPU-accelerated flow animation along transmission lines.
 *
 * Uses PIXI.ParticleContainer for a single draw call regardless of particle count.
 * Particle positions are sampled from pre-computed bezier LUTs in {@link WireLayer}.
 *
 * Performance budget: 800 edges × 3 particles = 2400 particles — well within
 * ParticleContainer's 50 000-particle limit.
 *
 * See docs/engineering/15-pixi-renderer.md §Flow particles.
 */

import * as PIXI from 'pixi.js'
import type { BranchEdge } from '../../model/GridGraph'
import type { BezierLUT } from './WireLayer'

const PARTICLES_PER_EDGE = 3
const BASE_SPEED = 0.004
const LOAD_SPEED_MULT = 0.012

interface Particle {
  sprite: PIXI.Sprite
  lut: Float32Array   // ref to BezierLUT.points (64 samples)
  t: number           // 0–1 position along curve
  speed: number
}

export class ParticleLayer {
  readonly container: PIXI.ParticleContainer
  private particles: Particle[] = []
  private _visible = false

  constructor() {
    this.container = new PIXI.ParticleContainer(50_000, {
      position: true,
      tint:     true,
    })
    this.container.zIndex = 30
    this.container.visible = false
  }

  get flowVisible(): boolean { return this._visible }

  setFlowVisible(on: boolean): void {
    this._visible = on
    this.container.visible = on
  }

  /**
   * Rebuilds particles from the given edges and their LUTs.
   * Call after {@link WireLayer.update} to get fresh LUTs.
   */
  rebuild(
    edges: BranchEdge[],
    luts: ReadonlyMap<string, BezierLUT>,
    dotTexture: PIXI.Texture,
  ): void {
    // Remove old sprites
    this.container.removeChildren()
    this.particles = []

    for (const edge of edges) {
      if (!edge.connected) continue
      const lut = luts.get(edge.id)
      if (!lut) continue

      for (let i = 0; i < PARTICLES_PER_EDGE; i++) {
        const sprite = new PIXI.Sprite(dotTexture)
        sprite.anchor.set(0.5)
        sprite.width = sprite.height = 5
        sprite.tint = 0xffd060

        this.container.addChild(sprite)
        this.particles.push({
          sprite,
          lut:   lut.points,
          t:     i / PARTICLES_PER_EDGE,
          speed: BASE_SPEED + edge.loadFactor * LOAD_SPEED_MULT,
        })
      }
    }
  }

  /** Advance all particles one frame. Call from PIXI.Ticker. */
  tick(dt: number): void {
    if (!this._visible) return

    for (const p of this.particles) {
      p.t = (p.t + p.speed * dt) % 1
      const i = Math.floor(p.t * 63) * 2
      p.sprite.position.set(p.lut[i], p.lut[i + 1])
    }
  }

  destroy(): void { this.container.destroy({ children: true }) }
}
