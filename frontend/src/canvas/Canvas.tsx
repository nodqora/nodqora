import { useEffect, useMemo, useRef } from 'react'
import {
  Background,
  Controls,
  MarkerType,
  ReactFlow,
  useEdgesState,
  useNodesInitialized,
  useNodesState,
  useReactFlow,
  type Edge,
  type Node,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { NodeCard, type NodeCardData } from './NodeCard'
import { layout } from './layout'
import { edgeIsHighlighted, highlightFrom } from './highlight'
import { foldKey } from '../api/keys'
import { crossesTeams, ownersByNodeKey } from './crossTeam'
import { summarizeMetrics } from './metrics'
import { marksOf } from '../outcome/marks'
import type { Rosters } from '../outcome/plugins'
import type { Graph, Health, PluginRef, State } from '../api/types'

const COLUMN_WIDTH = 300
const ROW_HEIGHT = 132
// Declared rather than measured. XYFlow fits the viewport to the bounding box of what it knows, and
// a custom node whose size it has not measured yet contributes nothing to that box — which parks the
// whole graph unscaled in a corner on a cold load. Stating the size removes the race entirely.
const CARD_WIDTH = 236
const CARD_HEIGHT = 62
const METRIC_LINE_HEIGHT = 22
// ADR-0083's footer token is a line like any other, so it has to be declared for the same reason.
const BLIND_TOKEN_HEIGHT = 18
const NODE_TYPES = { card: NodeCard }

/**
 * ADR-0018's MVP canvas: pan, zoom, fit-to-screen, node rendering, an optional metric line behind a
 * canvas-level toggle, upstream/downstream highlighting on selection.
 *
 * Deliberately absent: minimap, filter by type, filter by health, collapse/expand, path highlighting,
 * layout switching. Most are small — they are deferred for clutter and scope discipline, so that the
 * MVP canvas proves the central experience rather than the feature list.
 *
 * **Environment is not a filter here.** It is a scope, rendered as a switcher above this component: a
 * node absent from an environment has no row, so there is nothing to filter.
 */
export function Canvas({
  graph,
  state,
  selectedKey,
  onSelect,
  showMetrics,
  rosters,
  registry,
  label,
}: {
  graph: Graph
  state: State | null
  selectedKey: string | null
  onSelect: (key: string | null) => void
  showMetrics: boolean
  rosters: Rosters
  registry: PluginRef[]
  label: (pluginId: string) => string
}) {
  const highlight = useMemo(
    () => (selectedKey ? highlightFrom(selectedKey, graph.edges) : null),
    [selectedKey, graph.edges],
  )

  const health = useMemo(() => {
    const byKey = new Map<string, Health>()
    state?.nodes.forEach((node) => byKey.set(foldKey(node.nodeKey), node.health))
    return byKey
  }, [state])

  const metricLines = useMemo(() => {
    const byKey = new Map<string, string | null>()
    state?.nodes.forEach((node) => byKey.set(foldKey(node.nodeKey), summarizeMetrics(node.metrics)))
    return byKey
  }, [state])

  const descriptors = useMemo(
    () => new Map(graph.typeDescriptors.map((descriptor) => [descriptor.type, descriptor])),
    [graph.typeDescriptors],
  )

  const laidOut = useMemo<Node[]>(() => {
    const placements = new Map(layout(graph.nodes, graph.edges).map((p) => [foldKey(p.key), p]))
    return graph.nodes.map((node) => {
      const folded = foldKey(node.key)
      const placement = placements.get(folded)
      // ADR-0083's two conditional marks. Both are empty on an ordinary day, because neither can
      // fire while every plugin's `outcome` is COMPLETE.
      const marks = marksOf(node, rosters, registry)
      const data: NodeCardData = {
        nodeKey: node.key,
        displayName: node.displayName,
        descriptor: node.type ? descriptors.get(node.type) : undefined,
        // A node with no state entry cannot happen — /state carries every key (ADR-0057) — but the
        // canvas must still render before the first /state response lands.
        health: health.get(folded) ?? 'UNKNOWN',
        metricLine: metricLines.get(folded) ?? null,
        showMetrics,
        emphasis: emphasisOf(folded, selectedKey, highlight),
        retained: marks.retained.map(label),
        blind: marks.blind.map(label),
      }
      const metricLine = data.showMetrics && data.metricLine ? METRIC_LINE_HEIGHT : 0
      const blindToken = data.blind.length > 0 ? BLIND_TOKEN_HEIGHT : 0
      return {
        id: node.key,
        type: 'card',
        // Left-to-right: the canvas is width-hungry and height-light, which is the standing
        // constraint the drawer spends a fifth of the width against (ADR-0016, ADR-0019).
        position: { x: (placement?.column ?? 0) * COLUMN_WIDTH, y: (placement?.row ?? 0) * ROW_HEIGHT },
        width: CARD_WIDTH,
        height: CARD_HEIGHT + metricLine + blindToken,
        data,
        draggable: false,
      }
    })
  }, [
    graph.nodes,
    graph.edges,
    descriptors,
    health,
    metricLines,
    showMetrics,
    selectedKey,
    highlight,
    rosters,
    registry,
    label,
  ])

  const owners = useMemo(() => ownersByNodeKey(graph.nodes), [graph.nodes])

  const drawn = useMemo<Edge[]>(
    () =>
      graph.edges.map((edge) => {
        const drawnEdge: Edge = {
          id: `${edge.fromKey}|${edge.relation}|${edge.toKey}`,
          source: edge.fromKey,
          target: edge.toKey,
          // ADR-0002: an arrowhead, on every edge, pointing the way data flows. The stored
          // direction is the same for all six relations, so a reader never has to know which of
          // them read against the flow — that difference survives only in the drawer's wording.
          markerEnd: { type: MarkerType.ArrowClosed, width: 18, height: 18 },
          animated: false,
        }
        const classes: string[] = []
        // ADR-0016: an edge treatment, not a layout axis. The fixture crosses at the connectors,
        // which is the hand-off the product exists to make visible.
        if (crossesTeams(edge, owners)) classes.push('edge-cross-team')
        if (highlight) {
          classes.push(edgeIsHighlighted(edge, highlight) ? 'edge-highlighted' : 'edge-dimmed')
        }
        if (classes.length > 0) drawnEdge.className = classes.join(' ')
        return drawnEdge
      }),
    [graph.edges, highlight, owners],
  )

  const [nodes, setNodes, onNodesChange] = useNodesState(laidOut)
  const [edges, setEdges, onEdgesChange] = useEdgesState(drawn)
  const flow = useReactFlow()
  const measured = useNodesInitialized()
  const fittedFor = useRef<string | null>(null)
  const pane = useRef<HTMLDivElement>(null)

  useEffect(() => setNodes(laidOut), [laidOut, setNodes])
  useEffect(() => setEdges(drawn), [drawn, setEdges])

  // ADR-0018 lists fit-to-screen as a control the user asks for and says nothing about what the
  // canvas is pointed at on load, so it also gets an initial viewport. Re-fitting on an environment
  // switch is the same act — a different graph, and the switcher's whole point is that the graph
  // changes rather than a label.
  //
  // Two things have to hold before it can run. XYFlow fits to the bounding box of the nodes it has
  // *measured*, so fitting before `useNodesInitialized` fits to nothing and parks the graph unscaled
  // in a corner. And it must run once per graph rather than on every render, or a poll landing would
  // yank the viewport out from under whoever is reading it.
  useEffect(() => {
    if (!measured || fittedFor.current === graph.environment.key) return
    fittedFor.current = graph.environment.key
    // Instant, not animated. The first `/state` response lands within a few milliseconds of mount
    // and replaces every node object; an in-flight fit animation interrupted by that re-render
    // settles wherever it had got to.
    const frame = requestAnimationFrame(() => void flow.fitView({ padding: 0.16 }))
    return () => cancelAnimationFrame(frame)
  }, [measured, graph.environment.key, flow])

  // ADR-0069: **pan to bring the node into view. Do not change zoom.**
  //
  // Not changing zoom is the load-bearing half. ADR-0016's layout is width-hungry and height-light,
  // so a user who has zoomed out to see the whole flow has done deliberate work, and zooming them to
  // one node destroys that to solve a problem panning has already solved. Zoom-to-fit stays where
  // ADR-0018 put it — an explicit control the user asks for.
  //
  // ADR-0097 makes a deep link the ordinary cold load plus a selection: fit, then pan if off-screen,
  // then select. This effect is declared *after* the fit above, so within one commit its frame
  // callback runs second and it measures a viewport the fit has already set. On the fixture the pan
  // is a no-op, because fit-to-screen shows the whole ten-node pipeline; it is kept for the graph
  // the fixture is not, where the selected node can land off-screen with nothing to correct it.
  //
  // A clicked node is by definition on screen, so this costs one branch on the common path rather
  // than needing to know *how* the selection was made.
  useEffect(() => {
    if (!measured || !selectedKey) return
    const frame = requestAnimationFrame(() => {
      const rect = pane.current?.getBoundingClientRect()
      const node = flow.getNode(selectedKey) ?? flow.getNodes().find((n) => foldKey(n.id) === foldKey(selectedKey))
      if (!rect || !node) return

      const width = node.width ?? CARD_WIDTH
      const height = node.height ?? CARD_HEIGHT
      const topLeft = flow.flowToScreenPosition(node.position)
      const bottomRight = flow.flowToScreenPosition({
        x: node.position.x + width,
        y: node.position.y + height,
      })
      const onScreen =
        topLeft.x >= rect.left &&
        topLeft.y >= rect.top &&
        bottomRight.x <= rect.right &&
        bottomRight.y <= rect.bottom
      if (onScreen) return

      flow.setCenter(node.position.x + width / 2, node.position.y + height / 2, {
        zoom: flow.getZoom(),
        duration: 220,
      })
    })
    return () => cancelAnimationFrame(frame)
  }, [measured, selectedKey, flow, nodes])

  return (
    <div className="canvas-pane" ref={pane}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={NODE_TYPES}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onNodeClick={(_, node) => onSelect(node.id)}
        onPaneClick={() => onSelect(null)}
        nodesConnectable={false}
        proOptions={{ hideAttribution: true }}
      >
        <Background gap={22} size={1} />
        {/* Pan, zoom, fit-to-screen. No minimap: near-useless at fixture scale, cheap to restore. */}
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  )
}

function emphasisOf(
  foldedKey: string,
  selectedKey: string | null,
  highlight: ReturnType<typeof highlightFrom> | null,
): NodeCardData['emphasis'] {
  if (!selectedKey || !highlight) return 'none'
  if (foldedKey === highlight.selected) return 'selected'
  if (highlight.upstream.has(foldedKey)) return 'upstream'
  if (highlight.downstream.has(foldedKey)) return 'downstream'
  return 'dimmed'
}
