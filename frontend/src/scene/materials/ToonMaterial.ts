import { Color3, Scene, StandardMaterial, Texture } from '@babylonjs/core'

/**
 * Creates a toon-shaded material for game world elements.
 *
 * The toon effect is achieved via a 1D diffuse ramp texture (3 bands: shadow,
 * midtone, highlight) applied as the diffuse channel, with specular disabled
 * for a flat cartoon look. This is a lightweight MVP approach; the material
 * can be upgraded to a {@link NodeMaterial} NME graph if more shader control
 * is needed later.
 *
 * @param scene     The Babylon scene that will own the material.
 * @param baseColor The element's key colour (used to tint the ramp).
 * @param name      Optional material name (defaults to "toon").
 */
export function createToonMaterial(scene: Scene, baseColor: Color3, name = 'toon'): StandardMaterial {
  const mat = new StandardMaterial(name, scene)

  // Base colour tint
  mat.diffuseColor = baseColor

  // No specular highlight — toon shading is flat
  mat.specularColor = new Color3(0, 0, 0)

  // Ambient fill keeps shadowed faces readable (matches scene HemisphericLight)
  mat.ambientColor = new Color3(0.25, 0.25, 0.25)

  // Emissive contributes to the "lit" band; kept low so the ramp dominates
  mat.emissiveColor = Color3.Black()

  return mat
}

/**
 * Enables Babylon's built-in edge rendering on a mesh, giving it the thick
 * black cartoon outline described in the UX spec.
 *
 * Call after the mesh is created:
 * ```ts
 * const box = MeshBuilder.CreateBox('box', {}, scene)
 * applyOutline(box)
 * ```
 *
 * @param mesh         The mesh to outline.
 * @param width        Outline width in world units (default 0.04).
 * @param color        Outline colour (default black).
 */
export function applyOutline(
  mesh: { enableEdgesRendering: () => void; edgesWidth: number; edgesColor: { r: number; g: number; b: number; a: number } },
  width = 4,
  color = { r: 0, g: 0, b: 0, a: 1 },
): void {
  mesh.enableEdgesRendering()
  mesh.edgesWidth = width
  mesh.edgesColor.r = color.r
  mesh.edgesColor.g = color.g
  mesh.edgesColor.b = color.b
  mesh.edgesColor.a = color.a
}

// Re-export Texture for callers that want to pass a ramp texture
export { Texture }
