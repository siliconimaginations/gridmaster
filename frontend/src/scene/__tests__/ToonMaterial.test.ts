import { describe, expect, it, vi } from 'vitest'

/**
 * Unit tests for {@link createToonMaterial} and {@link applyOutline}.
 *
 * Babylon.js `StandardMaterial` requires a real WebGL context, so we mock
 * `@babylonjs/core` and assert on the properties set by the factory functions.
 */

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

  class Color4 {
    constructor(
      public r = 0,
      public g = 0,
      public b = 0,
      public a = 1,
    ) {}
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

  // Minimal AbstractMesh stand-in — real mesh has many more members
  class AbstractMesh {
    enableEdgesRendering = vi.fn()
    edgesWidth = 0
    edgesColor: Color4 = new Color4()
  }

  return { AbstractMesh, Color3, Color4, Scene: class {}, StandardMaterial }
})

import { AbstractMesh, Color3, Color4 } from '@babylonjs/core'
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

  it('disables specular (specularColor = black)', () => {
    const mat = createToonMaterial(mockScene as never, new Color3(1, 1, 1))
    expect(mat.specularColor?.r).toBe(0)
    expect(mat.specularColor?.g).toBe(0)
    expect(mat.specularColor?.b).toBe(0)
  })
})

describe('applyOutline', () => {
  it('calls enableEdgesRendering on the mesh', () => {
    const mesh = new AbstractMesh()
    applyOutline(mesh as never)
    expect(mesh.enableEdgesRendering).toHaveBeenCalledOnce()
  })

  it('sets edgesWidth to the supplied width', () => {
    const mesh = new AbstractMesh()
    applyOutline(mesh as never, 6)
    expect(mesh.edgesWidth).toBe(6)
  })

  it('sets edgesColor to the supplied Color4', () => {
    const mesh = new AbstractMesh()
    const color = new Color4(1, 0, 0, 1)
    applyOutline(mesh as never, 4, color as never)
    expect(mesh.edgesColor).toBe(color)
  })

  it('defaults to opaque black outline', () => {
    const mesh = new AbstractMesh()
    applyOutline(mesh as never)
    expect(mesh.edgesColor.r).toBe(0)
    expect(mesh.edgesColor.g).toBe(0)
    expect(mesh.edgesColor.b).toBe(0)
    expect(mesh.edgesColor.a).toBe(1)
  })
})
