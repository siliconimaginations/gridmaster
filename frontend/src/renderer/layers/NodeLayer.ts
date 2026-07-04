/**
 * NodeLayer — painter-sorted bus sprites with voltage dots, MW state bars,
 *             and a LOD-aware icon system.
 *
 * Each bus gets a PIXI.Container with:
 *   - PIXI.Sprite (building texture) — visible at LOD 1 and 2
 *   - PIXI.Graphics voltage dot — visible at LOD 1 and 2
 *   - PIXI.Graphics MW state bar (generators only) — visible at LOD 2 only
 *   - PIXI.Graphics icon — visible at LOD 0 (far zoom); drawn per role/size
 *
 * LOD tier meanings (from LodController):
 *   0  (<0.35×): far zoom — icons only; no sprites, no labels
 *   1  (0.35–0.70×): medium zoom — scaled sprites + voltage dots
 *   2  (>0.70×): close zoom — full sprites, state bars, all labels
 *
 * Icon designs (drawn with PIXI.Graphics, no external assets):
 *   gen  — yellow circle with a dark lightning bolt
 *   sub  — indigo rounded square with a white cross (transformer symbol)
 *   load — city-skyline silhouette, complexity based on loadMw:
 *            town  (<120 MW): 3 buildings
 *            city  (120–450 MW): 5 buildings
 *            metro (>450 MW): 7 buildings with skyscraper peak
 *   Any role with hasVoltageViolation: red ring behind the icon.
 *
 * sortableChildren=true ensures painter order by bus.y.
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
type CitySize = 'town' | 'city' | 'metro'

export class NodeLayer {
  readonly container: PIXI.Container

  private busContainers = new Map<string, PIXI.Container>()
  /** Cached bus data for icon redraws (e.g. violation-state change at LOD 0). */
  private busData       = new Map<string, BusNode>()
  private _onClick?: ClickCallback

  constructor() {
    this.container = new PIXI.Container()
    this.container.sortableChildren = true
    this.container.zIndex = 20
  }

  onBusClick(cb: ClickCallback): void { this._onClick = cb }

  rebuild(graph: GridGraph, textures: BusTextures, lod: LodTier): void {
    this.container.removeChildren()
    this.busContainers.clear()
    this.busData.clear()

    for (const bus of graph.buses.values()) {
      const group = this.buildBusGroup(bus, textures, lod)
      group.zIndex = bus.y
      this.container.addChild(group)
      this.busContainers.set(bus.id, group)
      this.busData.set(bus.id, bus)
    }
  }

  refreshBus(bus: BusNode, lod: LodTier): void {
    const group = this.busContainers.get(bus.id)
    if (!group) return

    // Voltage dot is only meaningful when sprites are visible (LOD 1+)
    if (lod > 0) {
      const vdot = group.getChildByName('vdot') as PIXI.Graphics | null
      if (vdot) { vdot.clear(); drawVoltageDot(vdot, bus) }
    }

    if (bus.role === 'gen') {
      const sbar = group.getChildByName('sbar') as PIXI.Graphics | null
      if (sbar) { sbar.clear(); drawStateBar(sbar, bus); sbar.visible = lod >= 2 }
    }

    // Icon needs a redraw when at LOD 0 — violation ring may have changed
    if (lod === 0) {
      const icon = group.getChildByName('icon') as PIXI.Graphics | null
      if (icon) { icon.clear(); drawBusIcon(icon, bus) }
    }

    this.busData.set(bus.id, bus)
    group.zIndex = bus.y
  }

  applyLod(lod: LodTier): void {
    for (const [id, group] of this.busContainers) {
      const sprite = group.getChildByName('sprite') as PIXI.Sprite   | null
      const vdot   = group.getChildByName('vdot')   as PIXI.Graphics | null
      const sbar   = group.getChildByName('sbar')   as PIXI.Graphics | null
      const icon   = group.getChildByName('icon')   as PIXI.Graphics | null

      if (sprite) {
        sprite.visible = lod > 0
        if (lod > 0) sprite.scale.set(lod === 1 ? 0.75 : 1.0)
      }
      if (vdot) vdot.visible = lod > 0
      if (sbar) sbar.visible = lod >= 2
      if (icon) {
        icon.visible = lod === 0
        // Redraw icon when switching into LOD 0 to reflect latest violation state
        if (lod === 0) {
          const bus = this.busData.get(id)
          if (bus) { icon.clear(); drawBusIcon(icon, bus) }
        }
      }
    }
  }

  destroy(): void { this.container.destroy({ children: true }) }

  // ── Private ──────────────────────────────────────────────────────────────────

  private buildBusGroup(bus: BusNode, textures: BusTextures, lod: LodTier): PIXI.Container {
    const group = new PIXI.Container()
    group.position.set(bus.x, bus.y)
    group.eventMode = 'static'
    group.cursor    = 'pointer'
    group.on('pointertap', () => this._onClick?.(bus))

    // Sprite — hidden at LOD 0 (icon takes over)
    const params  = SPRITE_PARAMS[bus.role] ?? SPRITE_PARAMS.load
    const texture = textures[bus.role] ?? textures.load
    const sprite  = new PIXI.Sprite(texture)
    sprite.label   = 'sprite'
    sprite.x       = params.x
    sprite.y       = params.y
    sprite.width   = params.w
    sprite.height  = params.h
    sprite.visible = lod > 0
    group.addChild(sprite)

    // Voltage dot — hidden at LOD 0
    const vdot = new PIXI.Graphics()
    vdot.label   = 'vdot'
    vdot.visible = lod > 0
    drawVoltageDot(vdot, bus)
    group.addChild(vdot)

    // State bar (generators only, visible at LOD 2+)
    if (bus.role === 'gen') {
      const sbar = new PIXI.Graphics()
      sbar.label   = 'sbar'
      sbar.visible = lod >= 2
      drawStateBar(sbar, bus)
      group.addChild(sbar)
    }

    // Icon — visible only at LOD 0
    const icon = new PIXI.Graphics()
    icon.label   = 'icon'
    icon.visible = lod === 0
    drawBusIcon(icon, bus)
    group.addChild(icon)

    return group
  }
}

// ── Icon drawing (pixi.js v8 Graphics API) ────────────────────────────────────

/**
 * Draws an inline PIXI.Graphics icon for a bus at LOD 0.
 * Centered at (0, 0) within the bus container.
 */
function drawBusIcon(g: PIXI.Graphics, bus: BusNode): void {
  // Voltage-violation alert ring (drawn first, behind the icon body)
  if (bus.hasVoltageViolation) {
    g.circle(0, 0, 26)
     .stroke({ color: 0xff3030, width: 3 })
  }

  switch (bus.role) {
    case 'gen': {
      // Yellow circle
      g.circle(0, 0, 20)
       .fill(0xffd700)
       .stroke({ color: 0xb8860b, width: 1.5 })
      // Dark lightning bolt polygon (flat [x,y] pairs)
      //  Top right → right bump → notch → bottom → left bump → notch → back
      g.poly([3, -10, 6, -1, 1, -1, -3, 10, -6, 1, -1, 1])
       .fill(0x1a1a1a)
      break
    }

    case 'sub': {
      // Indigo rounded square
      g.roundRect(-18, -18, 36, 36, 5)
       .fill(0x3d2b8e)
       .stroke({ color: 0xffffff, alpha: 0.25, width: 1 })
      // White cross (transformer symbol) — horizontal bar
      g.moveTo(-12, 0).lineTo(12, 0)
       .stroke({ color: 0xffffff, width: 2.5 })
      // Vertical bar
      g.moveTo(0, -12).lineTo(0, 12)
       .stroke({ color: 0xffffff, width: 2.5 })
      // Terminal dots at each end
      for (const [dx, dy] of [[-12, 0], [12, 0], [0, -12], [0, 12]] as const) {
        g.circle(dx, dy, 3).fill(0xffffff)
      }
      break
    }

    case 'load': {
      // City skyline — rectangles anchored at y=0 (ground line), growing upward
      const size  = loadCitySize(bus)
      const bldgs = cityBuildings(size)
      const totalW = bldgs.reduce((s, b) => s + b.w, 0) + (bldgs.length - 1) * 3
      let bx = -Math.round(totalW / 2)
      for (const b of bldgs) {
        g.rect(bx, -b.h, b.w, b.h)
         .fill(0x7a8fa6)
         .stroke({ color: 0x4a5f70, width: 0.8 })
        // Small window near top of taller buildings
        if (b.h >= 24) {
          g.rect(bx + Math.round(b.w / 2) - 2, -b.h + 6, 4, 4).fill(0xffec8b)
        }
        bx += b.w + 3
      }
      break
    }
  }
}

// ── City-size helpers ─────────────────────────────────────────────────────────

interface Building { w: number; h: number }

function loadCitySize(bus: BusNode): CitySize {
  if (bus.loadMw < 120) return 'town'
  if (bus.loadMw <= 450) return 'city'
  return 'metro'
}

function cityBuildings(size: CitySize): Building[] {
  switch (size) {
    case 'town':
      // 3 buildings — small settlement
      return [{ w: 12, h: 22 }, { w: 14, h: 30 }, { w: 11, h: 18 }]
    case 'city':
      // 5 buildings — medium city
      return [
        { w: 10, h: 26 }, { w: 13, h: 38 }, { w: 15, h: 44 },
        { w: 12, h: 34 }, { w: 10, h: 22 },
      ]
    case 'metro':
      // 7 buildings — metropolis with central skyscraper
      return [
        { w: 9,  h: 28 }, { w: 11, h: 40 }, { w: 13, h: 50 },
        { w: 15, h: 62 }, { w: 13, h: 50 }, { w: 11, h: 38 }, { w: 9, h: 24 },
      ]
  }
}

// ── Sprite-tier helpers ───────────────────────────────────────────────────────

function drawVoltageDot(g: PIXI.Graphics, bus: BusNode): void {
  const zone  = voltageZone(bus.v)
  const color = VOLTAGE_COLORS[zone]
  const wo    = WIRE_OFFSET[bus.role] ?? -90
  g.circle(-30, wo - 4, 4)
   .fill(color)
   .stroke({ color: 0x000000, alpha: 0.6, width: 1 })
}

function drawStateBar(g: PIXI.Graphics, bus: BusNode): void {
  if (bus.role !== 'gen' || bus.genMaxMw <= 0) return

  const pct   = bus.genMw / bus.genMaxMw
  const wo    = WIRE_OFFSET.gen
  const yTop  = wo - 12
  const x     = -STATE_BAR_W / 2
  const fillW = Math.round(STATE_BAR_W * pct)

  const fillColor = pct > 0.85 ? 0xff3030 : pct > 0.60 ? 0xffa020 : 0x28cc60

  // Background track
  g.roundRect(x, yTop - STATE_BAR_H, STATE_BAR_W, STATE_BAR_H, 2)
   .fill({ color: 0x000000, alpha: 0.68 })
   .stroke({ color: 0xffffff, alpha: 0.22, width: 0.7 })

  // Fill
  if (fillW > 0) {
    g.roundRect(x, yTop - STATE_BAR_H, fillW, STATE_BAR_H, 2)
     .fill(fillColor)
  }

  // Threshold markers at 60% and 85%
  for (const t of [0.60, 0.85]) {
    const mx = x + STATE_BAR_W * t
    g.moveTo(mx, yTop - STATE_BAR_H)
     .lineTo(mx, yTop)
     .stroke({ color: 0x000000, alpha: 0.4, width: 0.8 })
  }
}

// ── Texture bag ───────────────────────────────────────────────────────────────

export interface BusTextures {
  gen:  PIXI.Texture
  sub:  PIXI.Texture
  load: PIXI.Texture
}
