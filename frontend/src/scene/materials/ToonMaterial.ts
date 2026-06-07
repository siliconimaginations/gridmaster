import { AbstractMesh, Color3, Color4, Scene, StandardMaterial } from '@babylonjs/core'

/**
 * Creates a flat-shaded cartoon material for game world elements.
 *
 * Specular is disabled and ambient fill is raised to approximate a toon look
 * (distinct light/shadow separation without smooth gradients). This is a
 * lightweight MVP foundation; it can be upgraded to a {@link NodeMaterial}
 * NME graph with a 1D diffuse ramp texture for a true multi-band cel-shade
 * effect once the asset pipeline is in place.
 *
 * @param scene     The Babylon scene that will own the material.
 * @param baseColor The element's key colour.
 * @param name      Optional material name (defaults to "toon").
 */
export function createToonMaterial(scene: Scene, baseColor: Color3, name = 'toon'): StandardMaterial {
  const mat = new StandardMaterial(name, scene)

  // Base colour
  mat.diffuseColor = baseColor

  // No specular highlight — keeps the flat cartoon look
  mat.specularColor = new Color3(0, 0, 0)

  // Raised ambient so shadowed faces stay readable (matches scene HemisphericLight)
  mat.ambientColor = new Color3(0.25, 0.25, 0.25)

  return mat
}

/**
 * Enables Babylon's built-in edge rendering on a mesh, giving it the thick
 * black cartoon outline described in the UX spec.
 *
 * @param mesh   The mesh to outline.
 * @param width  Outline width in screen-space pixels (default 4).
 * @param color  Outline colour as {@link Color4} (default opaque black).
 */
export function applyOutline(mesh: AbstractMesh, width = 4, color = new Color4(0, 0, 0, 1)): void {
  mesh.enableEdgesRendering()
  mesh.edgesWidth = width
  mesh.edgesColor = color
}
