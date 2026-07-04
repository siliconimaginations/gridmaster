/**
 * LodController — maps viewport zoom scale to a LOD tier and fires callbacks
 * when the tier changes.
 *
 * Tiers:
 *   0  (<0.35×): zone/aggregate view — no bus sprites, no labels
 *   1  (0.35–0.70×): small sprites, bus ID labels, voltage dots
 *   2  (>0.70×): full sprites, ID + name labels, state bars
 *
 * See docs/engineering/15-pixi-renderer.md §LOD tiers.
 */

export type LodTier = 0 | 1 | 2

const TIER_THRESHOLDS: [number, number, LodTier][] = [
  [0,     0.35, 0],
  [0.35,  0.70, 1],
  [0.70, Infinity, 2],
]

type TierChangeCallback = (tier: LodTier, prev: LodTier) => void

export class LodController {
  private _tier: LodTier = 1
  private _callbacks: TierChangeCallback[] = []

  get tier(): LodTier { return this._tier }

  /** Call this from the pixi-viewport `zoomed` event. */
  update(scale: number): void {
    const next = this.scaleToTier(scale)
    if (next !== this._tier) {
      const prev = this._tier
      this._tier = next
      this._callbacks.forEach(cb => cb(next, prev))
    }
  }

  /** Register a callback invoked when the tier changes. */
  onChange(cb: TierChangeCallback): () => void {
    this._callbacks.push(cb)
    return () => { this._callbacks = this._callbacks.filter(x => x !== cb) }
  }

  private scaleToTier(scale: number): LodTier {
    for (const [lo, hi, tier] of TIER_THRESHOLDS) {
      if (scale >= lo && scale < hi) return tier
    }
    return 2
  }
}
