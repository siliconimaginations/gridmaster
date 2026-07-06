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

/**
 * Picks the sprite for a bus: generators get a fuel-type-specific texture
 * (Coal/Gas/Hydro/Wind/Solar) when one exists, falling back to the generic
 * generator sprite for unmapped fuel types (NUCLEAR, OIL, OTHER, or a
 * missing genByFuel entry). Non-generator buses use their role's texture
 * as before (#375).
 */
export function resolveBusTexture(bus: BusNode, textures: BusTextures): PIXI.Texture {
  if (bus.role === 'gen') {
    const byFuel = bus.fuelType ? textures.genByFuel?.[bus.fuelType.toUpperCase()] : undefined
    return byFuel ?? textures.gen
  }
  return textures[bus.role] ?? textures.load
}

export class NodeLayer {
  readonly container: PIXI.Container

  private busContainers = new Map<string, PIXI.Container>()
  /** Cached bus data for icon redraws (e.g. violation-state change at LOD 0). */
  private busData       = new Map<string, BusNode>()
  /**
   * Per-sprite base scale derived from SPRITE_PARAMS[w/h] ÷ native texture
   * size, captured once in buildBusGroup. LOD tier changes must scale
   * *relative to this*, not set an absolute scale — otherwise crossing a
   * tier boundary re-inflates sprites toward native texture size (#359).
   */
  private spriteBaseScale = new Map<string, { x: number; y: number }>()
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
    this.spriteBaseScale.clear()

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

      const fuel = group.getChildByName('fuel') as PIXI.Graphics | null

      if (sprite) {
        sprite.visible = lod > 0
        if (lod > 0) {
          const base = this.spriteBaseScale.get(id)
          const factor = lod === 1 ? 0.75 : 1.0
          if (base) {
            sprite.scale.set(base.x * factor, base.y * factor)
          }
        }
      }
      if (vdot) vdot.visible = lod > 0
      if (sbar) sbar.visible = lod >= 2
      if (fuel) fuel.visible = lod === 1
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
    const texture = resolveBusTexture(bus, textures)
    const sprite  = new PIXI.Sprite(texture)
    sprite.label   = 'sprite'
    sprite.x       = params.x
    sprite.y       = params.y
    sprite.width   = params.w
    sprite.height  = params.h
    sprite.visible = lod > 0
    group.addChild(sprite)
    // Capture the scale that width/height derived from the native texture
    // size — applyLod scales relative to this, never absolutely (#359).
    const baseScale = { x: sprite.scale.x, y: sprite.scale.y }
    this.spriteBaseScale.set(bus.id, baseScale)
    // Honor the current LOD tier's scale factor at construction time too,
    // so a group built while at tier 1 doesn't render at full (tier-2) size
    // until the next tier crossing fires applyLod.
    if (lod === 1) sprite.scale.set(baseScale.x * 0.75, baseScale.y * 0.75)

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

      // Fuel badge (generators only, visible at LOD 1 — the LOD-0 icon
      // already embeds the glyph, and LOD 2 shows full sprite detail) (#335)
      const fuel = new PIXI.Graphics()
      fuel.label   = 'fuel'
      fuel.visible = lod === 1
      drawFuelBadge(fuel, bus)
      group.addChild(fuel)
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
      // Fuel-type glyph inside the circle; falls back to the classic
      // lightning bolt when fuelType is unknown (#335)
      drawFuelIcon(g, bus.fuelType, 0, 0, 11)
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

// ── Fuel-type glyphs (#335) ───────────────────────────────────────────────────

/**
 * Draws a fuel-type glyph centred at (cx, cy) with radius `size`.
 *
 * Glyph set: GAS/COAL flame (orange / grey), NUCLEAR trefoil, WIND turbine,
 * SOLAR sun, HYDRO waves; unknown or missing fuel falls back to the classic
 * lightning bolt. All shapes are defined for a base radius of 10 and scaled
 * by `k = size / 10`, so the same glyph works inside the LOD-0 circle and
 * the smaller LOD-1 badge.
 *
 * Uses only Graphics primitives — no sprite assets (issue #335).
 */
export function drawFuelIcon(
  g: PIXI.Graphics,
  fuelType: string | undefined,
  cx: number,
  cy: number,
  size: number,
): void {
  const k = size / 10
  const mapPts = (pts: number[]): number[] =>
    pts.map((v, i) => (i % 2 === 0 ? cx + v * k : cy + v * k))

  switch (fuelType?.toUpperCase()) {
    case 'GAS': {
      g.poly(mapPts([0, -10, 6, 2, 4, 6, -4, 6, -6, 2])).fill(0xff7f2a)
      g.poly(mapPts([0, -8, 4, 1, 3, 5, -3, 5, -4, 1])).fill(0xffd24a)
      break
    }

    case 'COAL': {
      g.poly(mapPts([0, -10, 6, 2, 4, 6, -4, 6, -6, 2])).fill(0x4a4a4a)
      g.poly(mapPts([0, -8, 4, 1, 3, 5, -3, 5, -4, 1])).fill(0x777777)
      break
    }

    case 'NUCLEAR': {
      g.circle(cx, cy, 2 * k).fill(0x111111)
      for (const angleDeg of [90, 210, 330]) {
        const rad = (angleDeg * Math.PI) / 180
        const spread = (14 * Math.PI) / 180
        g.poly([
          cx + Math.cos(rad) * 3.5 * k, cy + Math.sin(rad) * 3.5 * k,
          cx + Math.cos(rad + spread) * 9 * k, cy + Math.sin(rad + spread) * 9 * k,
          cx + Math.cos(rad - spread) * 9 * k, cy + Math.sin(rad - spread) * 9 * k,
        ]).fill(0x111111)
      }
      break
    }

    case 'WIND': {
      g.circle(cx, cy, 1.5 * k).fill(0x1a4d80)
      for (const angleDeg of [0, 120, 240]) {
        const rad = (angleDeg * Math.PI) / 180
        const spread = (6 * Math.PI) / 180
        g.poly([
          cx, cy,
          cx + Math.cos(rad + spread) * 10 * k, cy + Math.sin(rad + spread) * 10 * k,
          cx + Math.cos(rad - spread) * 10 * k, cy + Math.sin(rad - spread) * 10 * k,
        ]).fill(0x2266cc)
      }
      break
    }

    case 'SOLAR': {
      g.circle(cx, cy, 4.5 * k).fill(0xcc5500)
      for (let i = 0; i < 8; i++) {
        const angle = (i * Math.PI) / 4
        g.moveTo(cx + Math.cos(angle) * 6 * k, cy + Math.sin(angle) * 6 * k)
          .lineTo(cx + Math.cos(angle) * 9.5 * k, cy + Math.sin(angle) * 9.5 * k)
          .stroke({ color: 0xcc5500, width: 1.5 * k })
      }
      break
    }

    case 'HYDRO': {
      for (const baseYRel of [-2, 3]) {
        const yBase = cy + baseYRel * k
        g.moveTo(cx - 8 * k, yBase)
          .quadraticCurveTo(cx - 4 * k, yBase - 5 * k, cx, yBase)
          .quadraticCurveTo(cx + 4 * k, yBase + 5 * k, cx + 8 * k, yBase)
          .stroke({ color: 0x1155cc, width: 2 * k })
      }
      break
    }

    default: {
      g.poly(mapPts([3, -10, 6, -1, 1, -1, -3, 10, -6, 1, -1, 1])).fill(0x1a1a1a)
    }
  }
}

/**
 * Draws the LOD-1 fuel badge for a generator bus: a dark disc anchored near
 * the sprite's roofline with the fuel glyph inside. Hidden at LOD 0 (the main
 * icon already contains the glyph) and LOD 2 (full sprite detail).
 */
function drawFuelBadge(g: PIXI.Graphics, bus: BusNode): void {
  if (bus.role !== 'gen') return
  const bx = 34
  const by = WIRE_OFFSET.gen + 10
  g.circle(bx, by, 12)
    .fill({ color: 0x10141a, alpha: 0.85 })
    .stroke({ color: 0xffffff, alpha: 0.3, width: 1 })
  drawFuelIcon(g, bus.fuelType, bx, by, 8)
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
  /** Fallback generator sprite, used when a bus's fuelType has no entry in [genByFuel] (#375). */
  gen:  PIXI.Texture
  sub:  PIXI.Texture
  load: PIXI.Texture
  /** Per-fuel-type generator sprites, keyed by uppercase FuelType (e.g. 'COAL', 'GAS') — #375. */
  genByFuel?: Partial<Record<string, PIXI.Texture>>
}
