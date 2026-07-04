/**
 * NodeLayer — painter-sorted bus sprites with voltage dots and MW state bars.
 *
 * Each bus gets a PIXI.Container with:
 *   - PIXI.Sprite (building texture)
 *   - PIXI.Graphics voltage dot (bottom-left of sprite)
 *   - PIXI.Graphics MW state bar (generators only, above sprite)
 *
 * sortableChildren=true on the layer container ensures painter order by bus.y.
 *
 * See docs/engineering/15-pixi-renderer.md §Node layer.
 */

import * as PIXI from 'pixi.js'
import type { BusNode, GridGraph } from '../../model/GridGraph'
import { voltageZone, VOLTAGE_COLORS } from '../../model/GridGraph'
import type { LodTier } from '../lod/LodController'

// Sprite anchor offsets (content-bottom = y=0, content-center = x=0)
const SPRITE_PARAMS: Record<string, { x: number; y: number; w: number; h: number }> = {
  gen:  { x: -64, y: -125, w: 130, h: 130 },
  sub:  { x: -72, y: -130, w: 145, h: 145 },
  load: { x: -61, y: -114, w: 120, h: 120 },
}

const WIRE_OFFSET: Record<string, number> = {
  gen:  -108,
  sub:  -92,
  load: -88,
}

const STATE_BAR_W = 64
const STATE_BAR_H = 8

type ClickCallback = (bus: BusNode) => void

export class NodeLayer {
  readonly container: PIXI.Container

  /** busId → its Container, for fast per-bus updates */
  private busContainers = new Map<string, PIXI.Container>()

  private _onClick?: ClickCallback

  constructor() {
    this.container = new PIXI.Container()
    this.container.sortableChildren = true
    this.container.zIndex = 20
  }

  onBusClick(cb: ClickCallback): void { this._onClick = cb }

  /**
   * Rebuilds bus sprites from scratch.
   * Called on network change (new topology) or first load.
   */
  rebuild(graph: GridGraph, textures: BusTextures, lod: LodTier): void {
    this.container.removeChildren()
    this.busContainers.clear()

    for (const bus of graph.buses.values()) {
      const group = this.buildBusGroup(bus, textures, lod)
      group.zIndex = bus.y     // painter sort
      this.container.addChild(group)
      this.busContainers.set(bus.id, group)
    }
  }

  /**
   * Fast update: refreshes voltage dots, state bar fill, and tint.
   * Does NOT rebuild sprite geometry — call {@link rebuild} for topology changes.
   */
  refreshBus(bus: BusNode, lod: LodTier): void {
    const group = this.busContainers.get(bus.id)
    if (!group) return

    // Voltage dot (tag=vdot)
    const vdot = group.getChildByName('vdot') as PIXI.Graphics | null
    if (vdot) {
      vdot.clear()
      drawVoltageDot(vdot, bus)
    }

    // State bar (tag=sbar) — generators only
    if (bus.role === 'gen') {
      const sbar = group.getChildByName('sbar') as PIXI.Graphics | null
      if (sbar) {
        sbar.clear()
        drawStateBar(sbar, bus)
        sbar.visible = lod >= 2
      }
    }

    // Update painter z-index in case y changed
    group.zIndex = bus.y
  }

  applyLod(lod: LodTier): void {
    for (const [, group] of this.busContainers) {
      const sbar = group.getChildByName('sbar') as PIXI.Graphics | null
      if (sbar) sbar.visible = lod >= 2

      // Scale sprites at tier 0/1
      const scale = lod === 0 ? 0.5 : lod === 1 ? 0.75 : 1.0
      const sprite = group.getChildByName('sprite') as PIXI.Sprite | null
      if (sprite) { sprite.scale.set(scale) }
    }
  }

  destroy(): void { this.container.destroy({ children: true }) }

  // ── Private helpers ──────────────────────────────────────────────────────────

  private buildBusGroup(bus: BusNode, textures: BusTextures, lod: LodTier): PIXI.Container {
    const group = new PIXI.Container()
    group.position.set(bus.x, bus.y)
    group.interactive = true
    group.cursor = 'pointer'
    group.on('pointertap', () => this._onClick?.(bus))

    // ── Sprite ──────────────────────────────────────────────────────
    const params  = SPRITE_PARAMS[bus.role] ?? SPRITE_PARAMS.load
    const texture = textures[bus.role] ?? textures.load
    const sprite  = new PIXI.Sprite(texture)
    sprite.name   = 'sprite'
    sprite.x      = params.x
    sprite.y      = params.y
    sprite.width  = params.w
    sprite.height = params.h
    group.addChild(sprite)

    // ── Voltage dot ─────────────────────────────────────────────────
    const vdot = new PIXI.Graphics()
    vdot.name = 'vdot'
    drawVoltageDot(vdot, bus)
    group.addChild(vdot)

    // ── State bar (generators only) ─────────────────────────────────
    if (bus.role === 'gen') {
      const sbar = new PIXI.Graphics()
      sbar.name = 'sbar'
      drawStateBar(sbar, bus)
      sbar.visible = lod >= 2
      group.addChild(sbar)
    }

    return group
  }
}

// ── Drawing helpers ────────────────────────────────────────────────────────────

function drawVoltageDot(g: PIXI.Graphics, bus: BusNode): void {
  const zone  = voltageZone(bus.v)
  const color = VOLTAGE_COLORS[zone]
  const wo    = WIRE_OFFSET[bus.role] ?? -90
  // Position: left of label area, at rooftop height
  g.beginFill(color)
   .lineStyle(1, 0x000000, 0.6)
   .drawCircle(-30, wo - 4, 4)
   .endFill()
}

function drawStateBar(g: PIXI.Graphics, bus: BusNode): void {
  if (bus.role !== 'gen' || bus.genMaxMw <= 0) return

  const pct = bus.genMw / bus.genMaxMw
  const wo  = WIRE_OFFSET.gen
  const yTop = wo - 12    // just above sprite top
  const x = -STATE_BAR_W / 2

  const fillColor = pct > 0.85 ? 0xff3030 : pct > 0.60 ? 0xffa020 : 0x28cc60

  // Background track
  g.beginFill(0x000000, 0.68)
   .lineStyle(0.7, 0xffffff, 0.22)
   .drawRoundedRect(x, yTop - STATE_BAR_H, STATE_BAR_W, STATE_BAR_H, 2)
   .endFill()

  // Fill
  if (pct > 0) {
    g.beginFill(fillColor)
     .drawRoundedRect(x, yTop - STATE_BAR_H, Math.round(STATE_BAR_W * pct), STATE_BAR_H, 2)
     .endFill()
  }

  // Threshold markers at 60% and 85%
  for (const t of [0.60, 0.85]) {
    const mx = x + STATE_BAR_W * t
    g.lineStyle(0.8, 0x000000, 0.4)
     .moveTo(mx, yTop - STATE_BAR_H)
     .lineTo(mx, yTop)
  }
}

// ── Texture bag ───────────────────────────────────────────────────────────────

export interface BusTextures {
  gen:  PIXI.Texture
  sub:  PIXI.Texture
  load: PIXI.Texture
}
