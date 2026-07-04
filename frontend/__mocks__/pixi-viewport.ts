/**
 * Manual Vitest mock for pixi-viewport v6.
 */
import { vi } from 'vitest'
import { Container } from './pixi.js'

export class Viewport extends Container {
  scale = { x: 1, set: vi.fn() }

  private _handlers: Record<string, ((...args: unknown[]) => void)[]> = {}

  drag()                          { return this }
  pinch()                         { return this }
  wheel()                         { return this }
  decelerate()                    { return this }
  clampZoom(_opts?: unknown)      { return this }

  on(event: string, cb: (...args: unknown[]) => void): this {
    if (!this._handlers[event]) this._handlers[event] = []
    this._handlers[event].push(cb)
    return this
  }

  /** Helper for tests: fire a viewport event. */
  emit(event: string, ...args: unknown[]): void {
    for (const cb of this._handlers[event] ?? []) cb(...args)
  }

  toScreen(x: number, y: number) { return { x, y } }

  addChildAt(child: Container, index: number): Container {
    this.children.splice(index, 0, child)
    return child
  }
}

export type IViewportOptions = Record<string, unknown>
