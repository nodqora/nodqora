// SPDX-License-Identifier: Apache-2.0
/**
 * What jsdom does not give XYFlow, and the two canvas tests that need a DOM both install.
 *
 * XYFlow reads three things jsdom does not provide: the pane's size, each node's size, and a
 * `ResizeObserver` to be told when either changes. Everything here supplies exactly those, and
 * nothing else.
 */

export const PANE = { width: 1200, height: 800 }

export interface Measurement {
  /**
   * Stop delivering resize callbacks, which is what a hidden tab does: the observer's callback is
   * dispatched on an animation frame, and a background tab gets no frames. Call it before rendering.
   */
  withhold(): void
}

/**
 * Sizes come from `offsetWidth`/`offsetHeight`, which jsdom pins at 0. Only a **px** inline style is
 * honoured here, and that qualifier is load-bearing rather than tidiness: the `.react-flow` pane
 * carries `width: 100%`, and a `parseFloat` that accepts it makes XYFlow believe the pane is 100
 * pixels wide — which still fits, still pans, and quietly moves every viewport assertion onto a
 * viewport no user has.
 */
export function installMeasurement(): Measurement {
  const px = (value: string): number | null => (value.endsWith('px') ? Number.parseFloat(value) : null)
  let withheld = false

  class ControllableResizeObserver {
    constructor(private readonly callback: ResizeObserverCallback) {}
    observe(element: Element) {
      if (withheld) return
      const entry = { target: element, contentRect: element.getBoundingClientRect() } as ResizeObserverEntry
      this.callback([entry], this as unknown as ResizeObserver)
    }
    unobserve() {}
    disconnect() {}
  }

  globalThis.ResizeObserver = ControllableResizeObserver as unknown as typeof ResizeObserver

  // XYFlow divides a measured node by the scale in its computed transform, to store sizes in flow
  // units rather than screen pixels. jsdom computes no transform, so the matrix it would parse is
  // the identity — which is also the truth here, since the sizes handed back below are already the
  // declared, unscaled ones.
  class IdentityMatrix {
    readonly m22 = 1
  }
  globalThis.DOMMatrixReadOnly = IdentityMatrix as unknown as typeof DOMMatrixReadOnly

  const define = (name: string, get: (element: HTMLElement) => number) =>
    Object.defineProperty(HTMLElement.prototype, name, {
      configurable: true,
      get(this: HTMLElement) {
        return get(this)
      },
    })

  define('offsetWidth', (element) => px(element.style.width) ?? PANE.width)
  define('offsetHeight', (element) => px(element.style.height) ?? PANE.height)
  Object.defineProperty(HTMLElement.prototype, 'getBoundingClientRect', {
    configurable: true,
    value(this: HTMLElement) {
      const width = px(this.style.width) ?? PANE.width
      const height = px(this.style.height) ?? PANE.height
      return { x: 0, y: 0, top: 0, left: 0, right: width, bottom: height, width, height, toJSON: () => ({}) }
    },
  })

  return {
    withhold() {
      withheld = true
    },
  }
}

/**
 * Opting out of React's act environment, for the same reason the canvas tests do not wrap their
 * settling in one: this canvas settles on the frame clock, not on React's queue, so every viewport
 * it produces lands after the render that caused it has long finished. Left on, `act` reports each
 * of those frames as an unwrapped update — hundreds of warnings for the behaviour working correctly.
 */
export function silenceActEnvironment() {
  Object.defineProperty(globalThis, 'IS_REACT_ACT_ENVIRONMENT', {
    configurable: true,
    get: () => false,
    // Testing Library turns the flag back on around every `render`, so pinning it takes a setter
    // that declines rather than an assignment it would overwrite.
    set: () => {},
  })
}

/** jsdom reports `visible` and has no way to change it, so the test owns the property. */
export function setVisibility(state: DocumentVisibilityState) {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
}
