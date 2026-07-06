/**
 * autoLayout — places bus nodes at canvas coordinates.
 *
 * Priority:
 * 1. Geographic: if ≥80% of buses have lat/lon, Mercator-project then fit.
 * 2. Force-directed: spring-embedder (no d3 dependency) with voltage-level lanes.
 * 3. Circular: fallback.
 *
 * Mutates BusNode.x / .y in-place and returns the same GridGraph.
 *
 * See docs/engineering/15-pixi-renderer.md §Layout algorithm.
 */

import type { GridGraph, BusNode } from '../../model/GridGraph'

// px inset from viewport edges — kept generous (rather than the terrain
// quad's actual physical edge) so buildings/sprites never sit right at the
// grass boundary. Bumped from 80 -> 220 per feedback that objects looked
// too close to the terrain edge, especially once zoomed out (see also the
// clampZoom minScale bump in PixiGridRenderer.ts, which limits how far a
// player can zoom out past the terrain quad in the first place).
const PADDING = 220

// ── Public API ────────────────────────────────────────────────────────────────

/**
 * Assigns canvas (x, y) to every bus node.
 *
 * @param graph   Mutable GridGraph (coordinates written in-place).
 * @param width   Viewport width in pixels.
 * @param height  Viewport height in pixels.
 */
export function layoutGrid(graph: GridGraph, width: number, height: number): GridGraph {
  const nodes = Array.from(graph.buses.values())
  if (nodes.length === 0) return graph

  const geoCount = nodes.filter(n => n.lat !== undefined && n.lon !== undefined).length
  const useGeo   = geoCount / nodes.length >= 0.80

  if (useGeo) {
    layoutGeographic(nodes, width, height)
  } else {
    layoutForceDirected(nodes, graph, width, height)
  }

  return graph
}

// ── Geographic layout ─────────────────────────────────────────────────────────

/** Simple Mercator projection fitted to the viewport bounding box. */
function layoutGeographic(nodes: BusNode[], width: number, height: number): void {
  const lats = nodes.filter(n => n.lat !== undefined).map(n => n.lat!)
  const lons = nodes.filter(n => n.lon !== undefined).map(n => n.lon!)

  const minLat = Math.min(...lats), maxLat = Math.max(...lats)
  const minLon = Math.min(...lons), maxLon = Math.max(...lons)

  const mercY = (lat: number): number =>
    Math.log(Math.tan(Math.PI / 4 + (lat * Math.PI) / 360))

  const minMY = mercY(minLat), maxMY = mercY(maxLat)

  const usableW = width  - PADDING * 2
  const usableH = height - PADDING * 2

  for (const node of nodes) {
    if (node.lat === undefined || node.lon === undefined) {
      // Place unlocated nodes at centre
      node.x = width / 2
      node.y = height / 2
      continue
    }
    node.x = PADDING + ((node.lon - minLon) / (maxLon - minLon || 1)) * usableW
    // Mercator: larger y = further south = higher on screen
    const my = mercY(node.lat)
    node.y = PADDING + (1 - (my - minMY) / (maxMY - minMY || 1)) * usableH
  }
}

// ── Force-directed layout ─────────────────────────────────────────────────────

const FD_ITERS       = 300
const FD_REPULSION   = 8_000   // node-node repulsion constant
const FD_SPRING_K    = 0.05    // edge spring pull
const FD_IDEAL_DIST  = 160     // ideal edge length px
const FD_DAMPING     = 0.85
const FD_VOLTAGE_K   = 0.04    // lane-attraction strength

/**
 * Spring-embedder with voltage-level lanes.
 * Generators are attracted upward, loads downward, subs to the middle.
 */
function layoutForceDirected(
  nodes: BusNode[],
  graph: GridGraph,
  width: number,
  height: number,
): void {
  const n = nodes.length

  // Seed positions: spread evenly on an ellipse
  const cx = width / 2, cy = height / 2
  const rx = (width  - PADDING * 2) / 2
  const ry = (height - PADDING * 2) / 2

  nodes.forEach((node, i) => {
    const angle = (2 * Math.PI * i) / n
    node.x = cx + rx * Math.cos(angle)
    node.y = cy + ry * Math.sin(angle)
  })

  const vx = new Float64Array(n)
  const vy = new Float64Array(n)

  // Pre-compute id → index map for O(1) edge lookups (avoids O(N) findIndex inside the loop)
  const idxMap = new Map<string, number>()
  nodes.forEach((node, i) => idxMap.set(node.id, i))

  // Voltage-lane targets: generators top third, loads bottom third, subs middle
  const laneY: Record<string, number> = {
    gen:  cy - ry * 0.55,
    load: cy + ry * 0.55,
    sub:  cy,
  }

  for (let iter = 0; iter < FD_ITERS; iter++) {
    const fx = new Float64Array(n)
    const fy = new Float64Array(n)

    // Repulsion (O(n²) — fine for <500 nodes; replace with BVH for larger grids)
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const dx = nodes[i].x - nodes[j].x
        const dy = nodes[i].y - nodes[j].y
        const d2 = dx * dx + dy * dy + 1
        const f  = FD_REPULSION / d2
        fx[i] += f * dx; fy[i] += f * dy
        fx[j] -= f * dx; fy[j] -= f * dy
      }
    }

    // Spring attraction along edges
    for (const edge of graph.edges) {
      if (!edge.connected) continue
      const ai = idxMap.get(edge.fromId) ?? -1
      const bi = idxMap.get(edge.toId)   ?? -1
      if (ai < 0 || bi < 0) continue

      const dx   = nodes[bi].x - nodes[ai].x
      const dy   = nodes[bi].y - nodes[ai].y
      const dist = Math.sqrt(dx * dx + dy * dy) || 1
      const f    = FD_SPRING_K * (dist - FD_IDEAL_DIST)
      const nx   = (dx / dist) * f
      const ny   = (dy / dist) * f
      fx[ai] += nx; fy[ai] += ny
      fx[bi] -= nx; fy[bi] -= ny
    }

    // Voltage-lane attraction (gentle vertical pull)
    nodes.forEach((node, i) => {
      fy[i] += FD_VOLTAGE_K * (laneY[node.role] - node.y)
    })

    // Integrate
    const cooling = 1 - iter / FD_ITERS
    nodes.forEach((node, i) => {
      vx[i] = (vx[i] + fx[i]) * FD_DAMPING
      vy[i] = (vy[i] + fy[i]) * FD_DAMPING
      node.x += vx[i] * cooling
      node.y += vy[i] * cooling
    })
  }

  // Fit to viewport
  fitToViewport(nodes, width, height)
}

// ── Helpers ────────────────────────────────────────────────────────────────────

function fitToViewport(nodes: BusNode[], width: number, height: number): void {
  if (nodes.length === 0) return
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  for (const n of nodes) {
    if (n.x < minX) minX = n.x; if (n.x > maxX) maxX = n.x
    if (n.y < minY) minY = n.y; if (n.y > maxY) maxY = n.y
  }
  const rangeX = maxX - minX || 1
  const rangeY = maxY - minY || 1
  const scaleX = (width  - PADDING * 2) / rangeX
  const scaleY = (height - PADDING * 2) / rangeY
  const scale  = Math.min(scaleX, scaleY)
  const offX   = PADDING + ((width  - PADDING * 2) - rangeX * scale) / 2
  const offY   = PADDING + ((height - PADDING * 2) - rangeY * scale) / 2
  for (const n of nodes) {
    n.x = offX + (n.x - minX) * scale
    n.y = offY + (n.y - minY) * scale
  }
}
