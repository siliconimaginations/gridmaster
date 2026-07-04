/**
 * PixiGridRenderer — drop-in replacement for SceneManager.
 *
 * Wraps a PIXI.Application + pixi-viewport and exposes the same interface
 * as SceneManager so App.tsx needs minimal changes:
 *
 *   updateNetwork(network, violations)  ← full topology update
 *   updateViolations(violations)        ← fast-path violations-only
 *   dispose()
 *
 * See docs/engineering/15-pixi-renderer.md for architecture details.
 */

import * as PIXI from 'pixi.js'
import { Viewport } from 'pixi-viewport'
import type { GridNetworkDto, ViolationDto, SelectedElementInfo } from '../api/types'
import { networkDtoToGridGraph, updateGridGraph } from '../model/GridGraph'
import type { GridGraph } from '../model/GridGraph'
import { layoutGrid } from './layout/autoLayout'
import { LodController, type LodTier } from './lod/LodController'
import { WireLayer } from './layers/WireLayer'
import { ParticleLayer } from './layers/ParticleLayer'
import { NodeLayer } from './layers/NodeLayer'
import type { BusTextures } from './layers/NodeLayer'

// World dimensions (virtual canvas size — viewport pans/zooms within it)
const WORLD_W = 2400
const WORLD_H = 1600

// Isometric terrain diamond tile size
const TILE_W = 192
const TILE_H = 96

type SelectCallback = (info: SelectedElementInfo | null) => void

export class PixiGridRenderer {
  private app: PIXI.Application
  private viewport: Viewport
  private lod: LodController

  private wires: WireLayer
  private nodes: NodeLayer
  private particles: ParticleLayer

  private terrain1: PIXI.TilingSprite | null = null
  private terrain2: PIXI.TilingSprite | null = null

  private textures: BusTextures | null = null
  private dotTexture: PIXI.Texture | null = null

  private graph: GridGraph | null = null

  // HTML label overlay — positioned as sibling to the canvas
  private labelOverlay: HTMLDivElement
  private labelElements = new Map<string, HTMLDivElement>()

  constructor(
    canvas: HTMLCanvasElement,
    private onSelect: SelectCallback,
  ) {
    this.app = new PIXI.Application({
      view:            canvas,
      width:           canvas.clientWidth,
      height:          canvas.clientHeight,
      backgroundColor: 0x4a7a42,
      antialias:       true,
      autoDensity:     true,
      resolution:      window.devicePixelRatio || 1,
    })

    this.viewport = new Viewport({
      screenWidth:  canvas.clientWidth,
      screenHeight: canvas.clientHeight,
      worldWidth:   WORLD_W,
      worldHeight:  WORLD_H,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      events: (this.app.renderer as any).events,
    } as ConstructorParameters<typeof Viewport>[0])

    this.viewport
      .drag()
      .pinch()
      .wheel()
      .decelerate()
      .clampZoom({ minScale: 0.15, maxScale: 4 })

    this.app.stage.addChild(this.viewport)

    this.lod = new LodController()
    this.wires = new WireLayer()
    this.nodes = new NodeLayer()
    this.particles = new ParticleLayer()

    // Layer z-order within viewport
    this.viewport.addChild(this.wires.container)
    this.viewport.addChild(this.nodes.container)
    this.viewport.addChild(this.particles.container)

    // Label HTML overlay
    this.labelOverlay = document.createElement('div')
    this.labelOverlay.style.cssText =
      'position:absolute;inset:0;pointer-events:none;overflow:hidden;'
    canvas.parentElement?.appendChild(this.labelOverlay)

    // Wire up LOD changes
    this.lod.onChange(tier => {
      this.nodes.applyLod(tier)
      this.updateLabelVisibility(tier)
    })

    this.viewport.on('zoomed', () => this.lod.update(this.viewport.scale.x))
    this.viewport.on('moved',  () => this.syncLabelPositions())

    // Bus click → select element
    this.nodes.onBusClick(bus => {
      this.onSelect({ elementType: 'BUS', elementId: bus.id })
    })

    // Tick — particles + label sync
    this.app.ticker.add((dt) => {
      this.particles.tick(dt)
      this.syncLabelPositions()
    })
  }

  // ── SceneManager-compatible API ────────────────────────────────────────────

  /** Full update: new network topology or first load. */
  updateNetwork(network: GridNetworkDto | null, violations: ViolationDto[]): void {
    if (!network) return
    if (!this.textures) {
      // Textures not loaded yet — queue update for after init
      void this.initAndUpdate(network, violations)
      return
    }
    this.applyNetwork(network, violations)
  }

  /** Fast-path: only violation highlights changed. */
  updateViolations(violations: ViolationDto[]): void {
    if (!this.graph) return
    // Re-derive violation flags without full graph rebuild
    for (const bus of this.graph.buses.values()) {
      const viol = violations.find(v => v.elementId === bus.id && v.elementType === 'BUS')
      bus.hasVoltageViolation = viol !== undefined
      bus.violationType = viol?.violationType as 'VOLTAGE_HIGH' | 'VOLTAGE_LOW' | undefined
      this.nodes.refreshBus(bus, this.lod.tier)
    }
  }

  dispose(): void {
    this.app.ticker.stop()
    this.wires.destroy()
    this.nodes.destroy()
    this.particles.destroy()
    this.labelOverlay.remove()
    this.app.destroy(false, { children: true, texture: true, baseTexture: true })
  }

  // ── Toggle flow animation (exposed to HUD buttons) ─────────────────────────

  setFlowVisible(on: boolean): void { this.particles.setFlowVisible(on) }
  get flowVisible(): boolean { return this.particles.flowVisible }

  // ── Private ────────────────────────────────────────────────────────────────

  private async initAndUpdate(
    network: GridNetworkDto,
    violations: ViolationDto[],
  ): Promise<void> {
    await this.loadTextures()
    this.buildTerrain()
    this.applyNetwork(network, violations)
  }

  private applyNetwork(network: GridNetworkDto, violations: ViolationDto[]): void {
    if (this.graph) {
      this.graph = updateGridGraph(this.graph, network, violations)
    } else {
      this.graph = networkDtoToGridGraph(network, violations)
      layoutGrid(this.graph, WORLD_W, WORLD_H)
    }

    this.wires.update(this.graph)
    this.nodes.rebuild(this.graph, this.textures!, this.lod.tier)
    this.particles.rebuild(
      this.graph.edges,
      this.wires.luts,
      this.dotTexture ?? PIXI.Texture.WHITE,
    )

    this.rebuildLabels(this.graph)
    this.syncLabelPositions()
  }

  // ── Terrain ─────────────────────────────────────────────────────────────────

  private buildTerrain(): void {
    const t1 = PIXI.Texture.from('sprite-terrain1')
    const t2 = PIXI.Texture.from('sprite-terrain2')

    this.terrain1 = new PIXI.TilingSprite(t1, WORLD_W, WORLD_H)
    this.terrain1.tilePosition.set(0, 0)
    this.terrain1.zIndex = 0

    this.terrain2 = new PIXI.TilingSprite(t2, WORLD_W, WORLD_H)
    this.terrain2.tilePosition.set(TILE_W / 2, TILE_H / 2)
    this.terrain2.zIndex = 1

    this.viewport.addChildAt(this.terrain1, 0)
    this.viewport.addChildAt(this.terrain2, 1)
  }

  // ── Texture loading ─────────────────────────────────────────────────────────

  /**
   * Loads sprite textures from the public asset directory.
   * Sprite files should be placed at /public/sprites/*.png.
   */
  private async loadTextures(): Promise<void> {
    await PIXI.Assets.load([
      { alias: 'sprite-gen',      src: '/sprites/sprite-gen.png'      },
      { alias: 'sprite-sub',      src: '/sprites/sprite-sub.png'      },
      { alias: 'sprite-city',     src: '/sprites/sprite-city.png'     },
      { alias: 'sprite-terrain1', src: '/sprites/sprite-terrain1.png' },
      { alias: 'sprite-terrain2', src: '/sprites/sprite-terrain2.png' },
    ])

    this.textures = {
      gen:  PIXI.Texture.from('sprite-gen'),
      sub:  PIXI.Texture.from('sprite-sub'),
      load: PIXI.Texture.from('sprite-city'),
    }

    // Tiny white circle texture for particles
    const g = new PIXI.Graphics()
    g.beginFill(0xffffff).drawCircle(0, 0, 4).endFill()
    this.dotTexture = this.app.renderer.generateTexture(g)
    g.destroy()
  }

  // ── HTML label overlay ──────────────────────────────────────────────────────

  private rebuildLabels(graph: GridGraph): void {
    // Pool: remove labels for deleted buses
    for (const [id, el] of this.labelElements) {
      if (!graph.buses.has(id)) {
        el.remove()
        this.labelElements.delete(id)
      }
    }

    for (const bus of graph.buses.values()) {
      if (this.labelElements.has(bus.id)) continue

      const el = document.createElement('div')
      el.style.cssText = [
        'position:absolute',
        'transform:translateX(-50%)',
        'pointer-events:none',
        'user-select:none',
        'text-align:center',
        'text-shadow:0 0 3px rgba(0,0,0,.9),0 0 6px rgba(0,0,0,.7)',
        'line-height:1.2',
      ].join(';')

      el.innerHTML = `
        <div style="font:600 10px/1 -apple-system,monospace;color:white">Bus ${bus.id}</div>
        <div style="font:8px/1 -apple-system,monospace;color:rgba(220,220,220,.9)">${bus.name}</div>
      `
      this.labelOverlay.appendChild(el)
      this.labelElements.set(bus.id, el)
    }
  }

  private syncLabelPositions(): void {
    if (!this.graph) return

    for (const bus of this.graph.buses.values()) {
      const el = this.labelElements.get(bus.id)
      if (!el) continue

      // Convert world coords to screen coords via viewport
      const screen = this.viewport.toScreen(bus.x, bus.y - 130)
      el.style.left = `${screen.x}px`
      el.style.top  = `${screen.y}px`
    }
  }

  private updateLabelVisibility(tier: LodTier): void {
    for (const el of this.labelElements.values()) {
      // Show bus ID at tier 1+, full name only at tier 2
      const nameEl = el.children[1] as HTMLElement
      el.style.visibility  = tier >= 1 ? 'visible' : 'hidden'
      nameEl.style.display = tier >= 2 ? 'block' : 'none'
    }
  }
}

