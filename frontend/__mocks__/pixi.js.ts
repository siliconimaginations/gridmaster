/**
 * Manual Vitest mock for pixi.js v8.
 *
 * Covers the subset used by PixiGridRenderer and its layers:
 *   Application, Container, Graphics (fluent v8 API), Sprite,
 *   Texture, Assets, TilingSprite.
 *
 * Usage: call `vi.mock('pixi.js')` in any test file that imports pixi.js code.
 */
import { vi } from 'vitest'

// ── Container ─────────────────────────────────────────────────────────────────

export class Container {
  children: Container[] = []
  label: string | undefined
  zIndex = 0
  visible = true
  cursor: string | undefined
  eventMode: string | undefined
  sortableChildren = false
  x = 0
  y = 0
  width  = 0
  height = 0

  position = { set: vi.fn((x: number, y: number) => { this.x = x; this.y = y }) }
  scale    = { set: vi.fn() }
  anchor   = { set: vi.fn() }

  addChild(child: Container): Container {
    this.children.push(child)
    return child
  }

  removeChildren(): void {
    this.children.length = 0
  }

  getChildByName(name: string): Container | null {
    return this.children.find(c => c.label === name) ?? null
  }

  on = vi.fn()

  destroy = vi.fn()
}

// ── Graphics (v8 fluent API) ───────────────────────────────────────────────────

export class Graphics extends Container {
  clear()                                                              { return this }
  circle(_x: number, _y: number, _r: number)                          { return this }
  fill(_c: unknown)                                                    { return this }
  stroke(_s: unknown)                                                  { return this }
  moveTo(_x: number, _y: number)                                       { return this }
  quadraticCurveTo(_cx: number, _cy: number, _x: number, _y: number)  { return this }
  roundRect(_x: number, _y: number, _w: number, _h: number, _r: number) { return this }
  lineTo(_x: number, _y: number)                                       { return this }
  poly(_points: number[])                                              { return this }
  rect(_x: number, _y: number, _w: number, _h: number)                { return this }
}

// ── Sprite ─────────────────────────────────────────────────────────────────────

/**
 * Mirrors real PixiJS v8 Sprite semantics closely enough to catch scale bugs
 * like #359: setting `.width`/`.height` derives `.scale` from the texture's
 * native size, and reading `.scale.x/.y` after a `.scale.set(x, y)` call
 * reflects the values actually passed. A shallow `scale = { set: vi.fn() }`
 * stub (as used previously) can't detect regressions where LOD code
 * overwrites scale with an absolute value instead of scaling relative to the
 * sprite's configured base size.
 */
export class Sprite extends Container {
  tint = 0xffffff
  texture: { orig?: { width: number; height: number } } | null

  constructor(texture: { orig?: { width: number; height: number } } | null = null) {
    super()
    this.texture = texture

    const texW = () => this.texture?.orig?.width ?? 1024
    const texH = () => this.texture?.orig?.height ?? 1024

    // Own `scale` object (shadows Container's fluent-set stub) whose x/y
    // reflect whatever was last actually passed to `.set()` or derived from
    // `.width`/`.height` — matching real PixiJS v8 Sprite semantics closely
    // enough to catch scale regressions like #359.
    this.scale = {
      x: 1,
      y: 1,
      set: vi.fn((x: number, y?: number) => {
        this.scale.x = x
        this.scale.y = y ?? x
      }),
    }

    Object.defineProperty(this, 'width', {
      configurable: true,
      get: () => this.scale.x * texW(),
      set: (v: number) => { this.scale.x = v / texW() },
    })
    Object.defineProperty(this, 'height', {
      configurable: true,
      get: () => this.scale.y * texH(),
      set: (v: number) => { this.scale.y = v / texH() },
    })
  }
}

// ── Texture ────────────────────────────────────────────────────────────────────

export class Texture {
  static WHITE = new Texture()
  static from(_src: string) { return new Texture() }
  orig = { width: 1024, height: 1024 }
}

// ── Assets ────────────────────────────────────────────────────────────────────

export const Assets = {
  load: vi.fn(() => Promise.resolve({})),
}

// ── TilingSprite ──────────────────────────────────────────────────────────────

export class TilingSprite extends Container {
  texture: unknown
  tilePosition = { set: vi.fn() }
  constructor({ texture, width, height }: { texture: unknown; width: number; height: number }) {
    super()
    this.texture = texture
    this.width   = width
    this.height  = height
  }
}

// ── Application ───────────────────────────────────────────────────────────────

export class Application {
  stage = new Container()
  ticker = {
    add:  vi.fn(),
    stop: vi.fn(),
  }
  renderer = {
    events: {},
    generateTexture: vi.fn(() => new Texture()),
  }
  async init(_opts: unknown): Promise<void> { /* no-op */ }
  destroy = vi.fn()
}
