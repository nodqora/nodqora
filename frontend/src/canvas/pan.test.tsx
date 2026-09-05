// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { cleanup, render, waitFor } from '@testing-library/react'
import { ReactFlowProvider, useNodesInitialized, useReactFlow, type ReactFlowInstance } from '@xyflow/react'
import { useEffect } from 'react'
import { readFileSync } from 'node:fs'
import { Canvas } from './Canvas'
import type { Graph } from '../api/types'

/**
 * ADR-0069's viewport rule, and ADR-0097's ordering of it on a cold load, asserted against the real
 * component rather than a restatement of its arithmetic.
 *
 * This is the **one** test file in the repo that needs a DOM, and it needs one for a specific
 * reason: the rule is not a function anywhere. "Pan if the node is off-screen, and do not change
 * zoom" is a comparison between a node's projected screen rectangle and the pane's, and both sides
 * of that comparison come from XYFlow's live viewport. Extracting the arithmetic into a pure module
 * would let this file run in `node` with the rest, but it would assert the extraction — the thing
 * that could still be wired to nothing, or run before the fit rather than after it. The ordering
 * against the fit is half of ADR-0097, so the wiring is what has to be under test.
 *
 * Everything below the fixture is the price of that: jsdom lays nothing out, so the measurements
 * XYFlow reads have to be supplied.
 */

const graph = JSON.parse(readFileSync('../fixtures/golden/graph-production.json', 'utf8')) as Graph

const PANE = { width: 1200, height: 800 }
/** Column 7 of ADR-0016's layering — the far right of the fixture, so it is what a fit puts at the edge. */
const FAR_RIGHT = 'trino-analytics'
/** Column 1, comfortably inside any viewport that fits the whole pipeline. */
const NEAR_LEFT = 'payments.events.raw.v1'

/**
 * XYFlow reads three things jsdom does not provide: the pane's size, each node's size, and a
 * `ResizeObserver` to be told when either changes.
 *
 * Sizes come from `offsetWidth`/`offsetHeight`, which jsdom pins at 0. Only a **px** inline style is
 * honoured here, and that qualifier is load-bearing rather than tidiness: the `.react-flow` pane
 * carries `width: 100%`, and a `parseFloat` that accepts it makes XYFlow believe the pane is 100
 * pixels wide — which still fits, still pans, and quietly moves every assertion in this file onto a
 * viewport no user has.
 */
function installMeasurement() {
  const px = (value: string): number | null => (value.endsWith('px') ? Number.parseFloat(value) : null)

  class ImmediateResizeObserver {
    constructor(private readonly callback: ResizeObserverCallback) {}
    observe(element: Element) {
      const entry = { target: element, contentRect: element.getBoundingClientRect() } as ResizeObserverEntry
      this.callback([entry], this as unknown as ResizeObserver)
    }
    unobserve() {}
    disconnect() {}
  }

  globalThis.ResizeObserver = ImmediateResizeObserver as unknown as typeof ResizeObserver

  // XYFlow divides a measured node by the scale in its computed transform, to store sizes in flow
  // units rather than screen pixels. jsdom computes no transform, so the matrix it would parse is
  // the identity — which is also the truth here, since the sizes handed back above are already the
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
}

installMeasurement()

// Opting out of React's act environment, for the same reason `settle` below is not wrapped in one:
// this canvas settles on the frame clock, not on React's queue, so every viewport here is produced
// after the render that caused it has long finished. Left on, `act` reports each of those frames as
// an unwrapped update — hundreds of warnings for the file's central behaviour working correctly.
Object.defineProperty(globalThis, 'IS_REACT_ACT_ENVIRONMENT', {
  configurable: true,
  get: () => false,
  // Testing Library turns the flag back on around every `render`, so pinning it takes a setter that
  // declines rather than an assignment it would overwrite.
  set: () => {},
})

type Flow = ReactFlowInstance
type Viewport = ReturnType<Flow['getViewport']>

// Hoisted rather than written inline in the JSX: `rosters`, `registry` and `label` are dependencies
// of the canvas's layout memo, so a fresh identity on every render makes it recompute the placements
// and re-set its nodes. Stable props keep this file measuring the viewport rather than a re-render
// storm of its own making.
const ROSTERS = { discovery: graph.plugins, health: [] }
const REGISTRY: never[] = []
const noop = () => {}
const labelOf = (pluginId: string) => pluginId

function Scene({
  graph: rendered,
  selectedKey,
  onReady,
}: {
  graph: Graph
  selectedKey: string | null
  onReady: (flow: Flow) => void
}) {
  return (
    <ReactFlowProvider>
      <Canvas
        graph={rendered}
        state={null}
        selectedKey={selectedKey}
        onSelect={noop}
        showMetrics={false}
        rosters={ROSTERS}
        registry={REGISTRY}
        label={labelOf}
      />
      <Ready onReady={onReady} />
    </ReactFlowProvider>
  )
}

/** Hands the test the same flow instance the canvas is driving, once its nodes are measured. */
function Ready({ onReady }: { onReady: (flow: Flow) => void }) {
  const flow = useReactFlow()
  const measured = useNodesInitialized()
  useEffect(() => {
    if (measured) onReady(flow)
  }, [measured, flow, onReady])
  return null
}

/** Renders the canvas and resolves once the ADR-0018 fit has settled, which is where a load leaves it. */
async function mount(selectedKey: string | null = null, rendered: Graph = graph) {
  let flow: Flow | null = null
  const capture = (instance: Flow) => void (flow = instance)
  const view = render(<Scene graph={rendered} selectedKey={selectedKey} onReady={capture} />)
  await waitFor(() => expect(flow).not.toBeNull())
  await settle()
  const ready = flow as unknown as Flow
  return {
    flow: ready,
    select: async (key: string) => {
      view.rerender(<Scene graph={rendered} selectedKey={key} onReady={capture} />)
      await settle()
    },
  }
}

/**
 * Long enough for the fit's `requestAnimationFrame`, the pan's frame behind it, and the 220ms
 * transition the pan starts. Both effects hang their work off a frame, so anything asserted before
 * one has run passes for the wrong reason.
 *
 * **Deliberately not wrapped in `act`.** Every viewport this file asserts is produced from a frame
 * callback, and `act` drains React's own queue without ever yielding to jsdom's frame clock — under
 * it the fit simply never runs, and every assertion below reads the untouched `zoom: 1` a canvas has
 * before it has been pointed at anything.
 */
const settle = () => new Promise((resolve) => setTimeout(resolve, 400))

/** Where a node's card actually lands, derived from the viewport rather than asked of the component. */
function screenRect(flow: Flow, key: string) {
  const node = flow.getNode(key)
  if (!node) throw new Error(`no node ${key}`)
  const { x, y, zoom } = flow.getViewport()
  const left = node.position.x * zoom + x
  const top = node.position.y * zoom + y
  return { left, top, right: left + (node.width ?? 0) * zoom, bottom: top + (node.height ?? 0) * zoom }
}

const isOnScreen = (flow: Flow, key: string) => {
  const rect = screenRect(flow, key)
  return rect.left >= 0 && rect.top >= 0 && rect.right <= PANE.width && rect.bottom <= PANE.height
}

/**
 * **The graph the fixture is not.** ADR-0097 keeps its step 2 for exactly this shape and says so:
 * on the ten-node fixture the pan is a documented no-op, because fit-to-screen shows the whole
 * pipeline. A twenty-deep chain runs the fit into XYFlow's minimum zoom, so the far end stays off
 * screen with nothing to correct it, which is the only condition under which step 2 does anything.
 */
const LONG_CHAIN = 20
const CHAIN_END = `chain-${LONG_CHAIN - 1}`
const wideGraph: Graph = (() => {
  const template = graph.nodes[0]
  const relation = graph.edges[0]
  if (!template || !relation) throw new Error('fixture carries no node to clone')
  const nodes = Array.from({ length: LONG_CHAIN }, (_, index) => ({ ...template, key: `chain-${index}` }))
  const edges = nodes.slice(1).map((node, index) => ({
    ...relation,
    fromKey: `chain-${index}`,
    toKey: node.key,
  }))
  return { ...graph, nodes, edges }
})()

describe('ADR-0069: a selection pans the node into view and does not change zoom', () => {
  afterEach(cleanup)

  it('brings an off-screen node into view on exactly the zoom it started from', async () => {
    const { flow, select } = await mount()

    // The deliberate work ADR-0069 exists to protect: a zoom the user chose, holding the far-right
    // node just past the edge. 0.6 is nobody's default — it is neither the fixture's fit nor the
    // library's clamp, so a zoom that survives this test survives because nothing rewrote it.
    flow.setViewport({ x: 0, y: 0, zoom: 0.6 })
    await settle()
    expect(isOnScreen(flow, FAR_RIGHT)).toBe(false)

    await select(FAR_RIGHT)

    expect(isOnScreen(flow, FAR_RIGHT)).toBe(true)
    expect(flow.getViewport().zoom).toBe(0.6)
  })

  it('reaches it by panning, which is the half that had to move', async () => {
    const { flow, select } = await mount()
    flow.setViewport({ x: 0, y: 0, zoom: 0.6 })
    await settle()

    await select(FAR_RIGHT)

    // Stated separately from the zoom, because a canvas that ignored the selection entirely would
    // pass the assertion above. Something has to have moved, and it has to be the translate.
    expect(flow.getViewport().x).not.toBe(0)
  })

  it('leaves the viewport untouched when the node is already on screen', async () => {
    const { flow, select } = await mount()
    const before: Viewport = { ...flow.getViewport() }
    expect(isOnScreen(flow, NEAR_LEFT)).toBe(true)

    await select(NEAR_LEFT)

    // ADR-0097 step 2 is *pan if off-screen*, and a click is the common path: recentring a node the
    // reader is already looking at moves the whole graph under them for nothing.
    expect(flow.getViewport()).toEqual(before)
  })
})

describe('ADR-0097: a deep link is the ordinary cold-load viewport, then a pan', () => {
  afterEach(cleanup)

  it('lands on the same zoom a plain load lands on', async () => {
    const plain = await mount()
    const coldLoadZoom = plain.flow.getViewport().zoom
    cleanup()

    const deepLinked = await mount(FAR_RIGHT)

    // Zoom-to-node was rejected on ADR-0018's own headline: the behaviour selection exists to
    // produce is upstream/downstream highlighting, and centring the target at a readable zoom draws
    // that highlight where nobody can see it. A reader arriving from a link has the least context,
    // and the neighbours are the context.
    expect(deepLinked.flow.getViewport().zoom).toBe(coldLoadZoom)
    expect(isOnScreen(deepLinked.flow, FAR_RIGHT)).toBe(true)
  })

  /**
   * The case the ordering exists for. ADR-0097's steps are *fit, then pan if off-screen*, and the
   * canvas cannot get that from declaration order alone: `fitView` hands its transform to XYFlow
   * rather than writing it, so a pan measuring the viewport one frame later still reads the pre-fit
   * one. It has to wait for the fit to land.
   *
   * This assertion is the reason to keep a graph the fixture is not. Against the fixture the pan is
   * a documented no-op, so every ordering — right, wrong, or absent — produces the same viewport,
   * and the bug this file found survived review sitting in plain sight.
   */
  it('pans a deep-linked node into view when the fit is too small to show it', async () => {
    const { flow } = await mount(CHAIN_END, wideGraph)

    expect(isOnScreen(flow, CHAIN_END)).toBe(true)
  })
})
