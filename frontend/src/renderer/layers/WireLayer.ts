/**
 * WireLayer — draws transmission lines as catenary bezier curves.
 *
 * Lines are drawn above terrain and below bus sprites (z=10).
 * Color encodes line loading, via the isNearLimit/isOverloaded flags computed
 * in model/GridGraph.ts:
 *   blue  (<= 70%)  — under-utilised
 *   amber (70–90%)  — approaching limit (isNearLimit)
 *   red   (> 90%)   — near/over limit (isOverloaded)
 *
 * These thresholds are aligned with the Babylon.js renderer's lineMesh.ts
 * (issue #395) — the two renderers previously used different cutoffs
 * (60%/85% here vs 70%/90% there), which was a confusing inconsistency.
 *
 * See docs/engineering/15-pixi-renderer.md §Catenary wires.
 */

import * as PIXI from 'pixi.js'
import type { GridGraph, BranchEdge, BusNode } from '../../model/GridGraph'

// Rooftop attachment offsets per bus role (local y, negative = above ground)
const WIRE_OFFSET: Record<string, number> = {
  gen:  -108,
  sub:  -92,
  load: -88,
}

const LINE_COLOR_NORMAL     = 0x4a8ab0
const LINE_COLOR_NEAR_LIMIT = 0xc88820
const LINE_COLOR_OVERLOAD   = 0xdd3030

export interface BezierLUT {
  /** Flat array: [x0,y0, x1,y1, … x63,y63] (128 floats, 64 samples). */
  points: Float32Array
  edgeId: string
}

export class WireLayer {
  readonly container: PIXI.Container

  private graphics: PIXI.Graphics
  private _luts: Map<string, BezierLUT> = new Map()

  constructor() {
    this.container = new PIXI.Container()
    this.container.zIndex = 10
    this.graphics = new PIXI.Graphics()
    this.container.addChild(this.graphics)
  }

  /** (Re)draws all transmission lines and pre-computes bezier LUTs. */
  update(graph: GridGraph): void {
    this.graphics.clear()
    this._luts.clear()

    for (const edge of graph.edges) {
      if (!edge.connected) continue

      const from = graph.buses.get(edge.fromId)
      const to   = graph.buses.get(edge.toId)
      if (!from || !to) continue

      const geom = this.catenaryGeom(from, to)
      this.drawLine(edge, geom)
      this._luts.set(edge.id, buildLUT(edge.id, geom))
    }
  }

  /** Returns precomputed bezier LUTs for {@link ParticleLayer}. */
  get luts(): ReadonlyMap<string, BezierLUT> { return this._luts }

  private catenaryGeom(from: BusNode, to: BusNode) {
    const ax = from.x
    const ay = from.y + (WIRE_OFFSET[from.role] ?? -90)
    const bx = to.x
    const by = to.y + (WIRE_OFFSET[to.role] ?? -90)
    const dist  = Math.hypot(bx - ax, by - ay)
    const droop = Math.min(dist * 0.07, 28)
    return { ax, ay, bx, by, cx: (ax + bx) / 2, cy: (ay + by) / 2 + droop }
  }

  private drawLine(
    edge: BranchEdge,
    { ax, ay, bx, by, cx, cy }: ReturnType<typeof this.catenaryGeom>,
  ): void {
    const color = edge.isOverloaded
      ? LINE_COLOR_OVERLOAD
      : edge.isNearLimit
      ? LINE_COLOR_NEAR_LIMIT
      : LINE_COLOR_NORMAL

    const width = edge.isOverloaded ? 2.8 : edge.isNearLimit ? 2.2 : 1.8

    // pixi.js v8 Graphics API
    this.graphics
      .moveTo(ax, ay)
      .quadraticCurveTo(cx, cy, bx, by)
      .stroke({ color, width, cap: 'round' })
  }

  destroy(): void { this.container.destroy({ children: true }) }
}

// ── Bezier LUT builder ─────────────────────────────────────────────────────────

const LUT_SAMPLES = 64

function buildLUT(
  edgeId: string,
  { ax, ay, bx, by, cx, cy }: { ax: number; ay: number; bx: number; by: number; cx: number; cy: number },
): BezierLUT {
  const points = new Float32Array(LUT_SAMPLES * 2)
  for (let i = 0; i < LUT_SAMPLES; i++) {
    const t = i / (LUT_SAMPLES - 1)
    const u = 1 - t
    points[i * 2]     = u * u * ax + 2 * u * t * cx + t * t * bx
    points[i * 2 + 1] = u * u * ay + 2 * u * t * cy + t * t * by
  }
  return { edgeId, points }
}
