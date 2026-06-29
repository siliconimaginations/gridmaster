/**
 * Shared Vitest manual mock for \`@babylonjs/core\`.
 *
 * Placed here so Vitest auto-hoists it for every test file — no per-file
 * `vi.mock(...)` boilerplate required.
 *
 * @see frontend/src/scene/__tests__/MeshRegistry.test.ts
 */
import { vi } from 'vitest'

export class Color3 {
  constructor(public r = 0, public g = 0, public b = 0) {}
  static Black() { return new Color3() }
}

export class Color4 {
  constructor(public r = 0, public g = 0, public b = 0, public a = 1) {}
}

export class Vector3 {
  constructor(public x = 0, public y = 0, public z = 0) {}
  clone() { return new Vector3(this.x, this.y, this.z) }
  static Distance(_a: unknown, _b: unknown) { return 50 }
  static Lerp(a: Vector3, b: Vector3, t: number) {
    return new Vector3(a.x + (b.x - a.x) * t, 0, a.z + (b.z - a.z) * t)
  }
  subtract(o: Vector3) { return new Vector3(this.x - o.x, 0, this.z - o.z) }
  normalize() { return this }
  negate() { return new Vector3(-this.x, 0, -this.z) }
}

export class StandardMaterial {
  diffuseColor: Color3 | undefined
  specularColor: Color3 | undefined
  ambientColor: Color3 | undefined
  emissiveColor: Color3 | undefined
  emissiveTexture: unknown = null
  backFaceCulling = true
  disableLighting = false
  dispose = vi.fn()
  constructor(public name: string, _s: unknown) {}
}

const makeMesh = () => ({
  position: new Vector3(),
  material: null as unknown,
  dispose: vi.fn(),
  rotation: new Vector3(),
  receiveShadows: false,
  isVisible: true,
  isPickable: true,
})

export const MeshBuilder = {
  CreateCylinder: vi.fn(makeMesh),
  CreateTorus: vi.fn(makeMesh),
  CreateBox: vi.fn(makeMesh),
  CreateTube: vi.fn(makeMesh),
  CreateGround: vi.fn(makeMesh),
}

export class ParticleSystem {
  particleTexture = null
  emitter = null
  minSize = 0
  maxSize = 0
  emitRate = 0
  minLifeTime = 0
  maxLifeTime = 0
  minEmitPower = 0
  maxEmitPower = 0
  direction1 = null
  direction2 = null
  color1 = null
  color2 = null
  colorDead = null
  constructor(public name: string, _cap: number, _s: unknown) {}
  start = vi.fn()
  dispose = vi.fn()
}

export class DynamicTexture {
  wrapU = 0; wrapV = 0; uOffset = 0; hasAlpha = false
  constructor(_n: string, _s: unknown, _scene: unknown) {}
  getContext() {
    return {
      clearRect: vi.fn(), fillRect: vi.fn(),
      fillStyle: '',
      beginPath: vi.fn(), arc: vi.fn(), fill: vi.fn(),
    }
  }
  update = vi.fn()
}
