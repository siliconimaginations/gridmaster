import { describe, expect, it, vi } from 'vitest'

/**
 * Unit tests for {@link createToonMaterial} and {@link applyOutline}.
 *
 * Babylon.js `StandardMaterial` calls WebGL under the hood, so we mock
 * the Scene dependency and assert on the properties we set.
 */

// Mock @babylonjs/core so tests run in jsdom without a real WebGL context
vi.mock('@babylonjs/core', () => {
  class Color3 {
    constructor(
      public r = 0,
      public g = 0,
      public b = 0,
    ) {}
    static Black() {
      return new Color3(0, 0, 0)
    }
  }

  class StandardMaterial {
    name: string
    diffuseColor: Color3 | undefined
    specularColor: Color3 | undefined
    ambientColor: Color3 | undefined
    emissiveColor: Color3 | undefined
    constructor(name: string, _scene: unknown) {
      this.name = name
    }
  }

  return { Color3, Scene: class {}, StandardMaterial, Texture: class {} }
})

import { Color3 } from '@babylonjs/core'
import { applyOutline, createToonMaterial } from '../materials/ToonMaterial'

describe('createToonMaterial', () => {
  const mockScene = {}

  it('returns a StandardMaterial with the supplied name', () => {
    const mat = createToonMaterial(mockScene as never, new Color3(1, 0, 0), 'myMat')
    expect(mat.name).toBe('myMat')
  })

  it('defaults name to "toon"', () => {
    const mat = createToonMaterial(mockScene as never, new Color3(0, 1, 0))
    expect(mat.name).toBe('toon')
  })

  it('sets diffuseColor to the supplied base color', () => {
    const color = new Color3(0.5, 0.3, 0.1)
    const mat = createToonMaterial(mockScene as never, color)
    expect(mat.diffuseColor).toBe(color)
  })

  it('disables specular highlight (specularColor = black)', () => {
    const mat = createToonMaterial(mockScene as never, new Color3(1, 1, 1))
    expect(mat.specularColor?.r).toBe(0)
    expect(mat.specularColor?.g).toBe(0)
    expect(mat.specularColor?.b).toBe(0)
  })
})

describe('applyOutline', () => {
  it('calls enableEdgesRendering on the mesh', () => {
    const mesh = {
      enableEdgesRendering: vi.fn(),
      edgesWidth: 0,
      edgesColor: { r: 0, g: 0, b: 0, a: 0 },
    }
    applyOutline(mesh)
    expect(mesh.enableEdgesRendering).toHaveBeenCalledOnce()
  })

  it('sets edgesWidth to the supplied width', () => {
    const mesh = {
      enableEdgesRendering: vi.fn(),
      edgesWidth: 0,
      edgesColor: { r: 0, g: 0, b: 0, a: 0 },
    }
    applyOutline(mesh, 6)
    expect(mesh.edgesWidth).toBe(6)
  })

  it('defaults to black outline', () => {
    const mesh = {
      enableEdgesRendering: vi.fn(),
      edgesWidth: 0,
      edgesColor: { r: 1, g: 1, b: 1, a: 1 },
    }
    applyOutline(mesh)
    expect(mesh.edgesColor.r).toBe(0)
    expect(mesh.edgesColor.g).toBe(0)
    expect(mesh.edgesColor.b).toBe(0)
  })
})
