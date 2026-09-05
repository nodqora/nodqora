// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, waitFor } from '@testing-library/react'
import { ReactFlowProvider } from '@xyflow/react'
import { readFileSync } from 'node:fs'
import { Canvas } from './Canvas'
import { installMeasurement, setVisibility, silenceActEnvironment } from './measurement.testkit'
import type { Graph } from '../api/types'

/**
 * A canvas that mounts in a hidden tab, which is the one way this component can fail without
 * looking like it has failed.
 *
 * XYFlow measures a node once, from a `ResizeObserver` callback delivered on an animation frame. A
 * background tab gets no frames, so the callback never runs — and because the card sizes never
 * subsequently change, the observer never fires again either. The store keeps `measured` and
 * `handleBounds` undefined on every node, and an edge whose endpoints have no handle position
 * cannot be placed.
 *
 * ADR-0016's card size is *declared* rather than measured, so the cards draw anyway. What the
 * reader gets is a full graph of healthy nodes with **no arrows between any of them** — a topology
 * canvas quietly asserting that nothing is connected to anything. Neither switching back to the tab
 * nor fit-to-screen repairs it; only a reload does.
 *
 * Asserted against the real component rather than a restatement, for the reason `pan.test.tsx`
 * gives: the wiring is the thing that can silently be connected to nothing.
 */

const graph = JSON.parse(readFileSync('../fixtures/golden/graph-production.json', 'utf8')) as Graph

const measurement = installMeasurement()

silenceActEnvironment()

const ROSTERS = { discovery: graph.plugins, health: [] }
const REGISTRY: never[] = []
const noop = () => {}
const labelOf = (pluginId: string) => pluginId

afterEach(() => {
  cleanup()
  setVisibility('visible')
})

function mount() {
  return render(
    <ReactFlowProvider>
      <Canvas
        graph={graph}
        state={null}
        selectedKey={null}
        onSelect={noop}
        showMetrics={false}
        rosters={ROSTERS}
        registry={REGISTRY}
        label={labelOf}
      />
    </ReactFlowProvider>,
  )
}

describe('a canvas that mounts while its tab is hidden', () => {
  it('draws every edge once the tab becomes visible', async () => {
    // No frames, so nothing XYFlow observes is ever delivered — for the whole test, because the
    // sizes never change and the observer would have no second chance either.
    measurement.withhold()
    setVisibility('hidden')

    const { container } = mount()

    // The cards arrive on their declared size, which is what makes the failure invisible.
    await waitFor(() =>
      expect(container.querySelectorAll('.react-flow__node')).toHaveLength(graph.nodes.length),
    )
    expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(0)

    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))

    // Nine edges on the production fixture, and the number is the fixture's rather than a
    // constant here so that a graph that grows an edge does not quietly weaken this.
    await waitFor(() =>
      expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(graph.edges.length),
    )
  })

  it('draws them on a visible mount that no frame ever reaches', async () => {
    // `withhold` above is for the file, not the case: the observer stays silent here too, and the
    // tab is visible from the start. This is the race the listener alone would lose — a tab that
    // became visible before the effect could subscribe — and it is why the effect also asks once
    // on the way in rather than only on the event.
    const { container } = mount()

    await waitFor(() =>
      expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(graph.edges.length),
    )
  })
})
