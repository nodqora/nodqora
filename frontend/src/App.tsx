// SPDX-License-Identifier: Apache-2.0
import { useCallback, useEffect, useMemo, useState } from 'react'
import { ReactFlowProvider } from '@xyflow/react'
import { api } from './api/client'
import { sameKey } from './api/keys'
import { Canvas } from './canvas/Canvas'
import { EmptyCanvas } from './canvas/EmptyCanvas'
import { FirstRun } from './firstrun/FirstRun'
import { isFirstRun } from './firstrun/unconfigured'
import { emptyStateOf } from './canvas/emptyState'
import { Drawer } from './inspector/Drawer'
import { UnresolvedDrawer } from './inspector/UnresolvedDrawer'
import { Banners } from './outcome/Banners'
import { PluginsChip } from './outcome/PluginsChip'
import { bannersOf, labeller, type Rosters } from './outcome/plugins'
import { blindPlugins, retainedSources } from './outcome/marks'
import { SearchBox } from './search/SearchBox'
import {
  defaultEnvironmentKey,
  environmentIsKnown,
  formatRoute,
  parseRoute,
  type Route,
} from './routing/route'
import { needsCanonicalizing, resolveNode, unresolvedStateOf } from './routing/resolve'
import type { Graph, Meta, PluginOutcome, State } from './api/types'

export function App() {
  const [meta, setMeta] = useState<Meta | null>(null)
  const [graph, setGraph] = useState<Graph | null>(null)
  const [state, setState] = useState<State | null>(null)
  // ADR-0018: metrics are opt-in, default on, so users can drop the overlay to avoid clutter.
  const [showMetrics, setShowMetrics] = useState(true)
  const [error, setError] = useState<string | null>(null)

  /**
   * ADR-0092: the URL is the state. There are exactly two pieces of it — the environment as a path
   * segment and the selected node as a query parameter — and the search query is deliberately not
   * one of them.
   *
   * No router library. The grammar is two productions and one of them is optional, so a dependency
   * would buy matching that `parseRoute` does in a regular expression.
   */
  const [route, setRoute] = useState<Route>(() => parseRoute(location.pathname, location.search))

  const navigate = useCallback((next: Route, mode: 'push' | 'replace') => {
    if (next.environmentKey === null) return
    const url = formatRoute(next.environmentKey, next.nodeKey)
    if (mode === 'push') {
      history.pushState(null, '', url)
    } else {
      history.replaceState(null, '', url)
    }
    setRoute(next)
  }, [])

  // Back and Forward are the browser's, so the app only listens.
  useEffect(() => {
    const onPop = () => setRoute(parseRoute(location.pathname, location.search))
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  useEffect(() => {
    api
      .meta()
      .then(setMeta)
      .catch((cause: Error) => setError(cause.message))
  }, [])

  // ADR-0093: bare `/` redirects to the first configured environment, so the root's ambiguity lasts
  // zero screens and the address bar is explicit immediately afterwards. `replace`, because the
  // unscoped URL is not a place the reader should be able to go Back to.
  useEffect(() => {
    if (!meta || route.environmentKey !== null) return
    const first = defaultEnvironmentKey(meta.environments)
    if (first !== null) navigate({ environmentKey: first, nodeKey: route.nodeKey }, 'replace')
  }, [meta, route.environmentKey, route.nodeKey, navigate])

  // ADR-0052 and ADR-0093: the environment key is matched **exactly**. It is operator-authored
  // config rather than discovered data, so it keeps one comparison rule across the stack.
  const known = meta !== null && route.environmentKey !== null && environmentIsKnown(meta.environments, route.environmentKey)
  const environmentKey = known ? route.environmentKey : null

  // A scope change empties both halves rather than leaving the previous environment's graph on
  // screen under the new environment's name (ADR-0004: environment is the frame, not a filter).
  useEffect(() => {
    setGraph(null)
    setState(null)
  }, [environmentKey])

  // Two polls at the two real cadences, because ADR-0003's wall is a cadence boundary: topology
  // moves when architecture moves, and health moves every refresh.
  usePoll(
    useCallback(async () => {
      if (!environmentKey) return
      setGraph(await api.graph(environmentKey))
    }, [environmentKey]),
    meta ? meta.refresh.graphSeconds * 1000 : null,
    setError,
  )

  usePoll(
    useCallback(async () => {
      if (!environmentKey) return
      setState(await api.state(environmentKey))
    }, [environmentKey]),
    meta ? meta.refresh.stateSeconds * 1000 : null,
    setError,
  )

  /**
   * ADR-0096: **every selection change pushes a history entry — including clearing to none.**
   *
   * Uniform push, because splitting it reopens ADR-0069's arbitration: pushing for peer-clicks and
   * replacing for canvas-clicks would give two paths to an identical state different history
   * effects. `replace` was rejected on the incident case — an SRE tracing upstream through the
   * drawer who presses Back would be thrown out of the app entirely, losing their place during
   * exactly the scenario the fixture's second health scenario exists to model. Deselection pushes
   * too, for one rule rather than two: Back from a closed drawer reopens the last node.
   */
  const select = useCallback(
    (key: string | null) => navigate({ environmentKey: route.environmentKey, nodeKey: key }, 'push'),
    [navigate, route.environmentKey],
  )

  /**
   * ADR-0096: **the `?node=` key rides an environment switch**, resolving in the new scope and
   * falling through to ADR-0095's states when absent.
   *
   * ADR-0004 makes production and staging `payments-api` two rows sharing the key, so carrying it is
   * a well-defined operation rather than a guess. This is not the affordance ADR-0070 declined — the
   * reader moved the scope themselves, using the one control whose entire job is moving it.
   *
   * Carry-only-when-present was rejected as self-defeating: it deselects exactly in the absent case,
   * making the drift the product exists to show the one branch that renders nothing.
   *
   * The two halves compose into something neither was aiming at: because the switch pushes and the
   * key rides, **Back after a switch returns to the same node in the previous environment**.
   */
  const switchEnvironment = (key: string) => navigate({ environmentKey: key, nodeKey: route.nodeKey }, 'push')

  const selectedNode = useMemo(
    () => (graph && route.nodeKey ? resolveNode(route.nodeKey, graph.nodes) : null),
    [graph, route.nodeKey],
  )

  // ADR-0094: a hit whose parameter differs from the stored key rewrites the URL to the stored key.
  // `replaceState`, not push — a canonicalization is not a selection change and must not appear in
  // the Back trail as a second visit to the same node. A miss is left exactly as given, because
  // there is no canonical form to rewrite it to.
  useEffect(() => {
    if (route.nodeKey && needsCanonicalizing(route.nodeKey, selectedNode) && selectedNode) {
      navigate({ environmentKey: route.environmentKey, nodeKey: selectedNode.key }, 'replace')
    }
  }, [route.nodeKey, route.environmentKey, selectedNode, navigate])

  const selectedState = useMemo(
    () => (selectedNode ? (state?.nodes.find((row) => sameKey(row.nodeKey, selectedNode.key)) ?? null) : null),
    [state, selectedNode],
  )

  const rosters: Rosters = useMemo(
    () => ({ discovery: graph?.plugins ?? [], health: state?.plugins ?? [] }),
    [graph, state],
  )
  const label = useMemo(() => labeller(meta?.plugins ?? []), [meta])

  /**
   * ADR-0081's affected node count, computed rather than guessed. A `PARTIAL` discovery poll that
   * confirmed eight nodes and retained two names exactly two — the distinction ADR-0056 said an
   * environment-level banner could not make, available here without a clock because ADR-0084 made
   * retention a per-key comparison.
   */
  const affectedBy = useCallback(
    (pair: PluginOutcome) => {
      if (!graph) return 0
      return pair.capability === 'DISCOVERY'
        ? graph.nodes.filter((node) => retainedSources(node, rosters.discovery).includes(pair.plugin)).length
        : graph.nodes.filter((node) => blindPlugins(node, rosters, meta?.plugins ?? []).includes(pair.plugin)).length
    },
    [graph, rosters, meta],
  )

  const banners = useMemo(
    () => (graph ? bannersOf(rosters, affectedBy, graph.nodes.length === 0) : []),
    [graph, rosters, affectedBy],
  )

  const unresolved =
    graph && route.nodeKey && selectedNode === null ? unresolvedStateOf(graph.nodes, graph.plugins) : null

  /**
   * ADR-0156: **the empty roster beats the URL, on every route, and the URL is not rewritten.**
   *
   * Checked before the known-check below, so `/`, `/environments/production` and
   * `/environments/anything?node=payments-api` all land here: there is one fact about this install
   * and it does not vary by URL. Ordering it the other way sends a stranger who was handed a link to
   * ADR-0093's not-found — *"No environment named `production`. Available:"* followed by nothing —
   * which frames a config-absent install as a **typo** and offers an empty repair.
   *
   * The URL is left exactly as given, because the pasted link is *correct in the future*: the reader
   * writes their config, restarts, reloads, and it resolves. A carried `?node=` is a deliberate
   * no-op, not a dropped input.
   */
  if (isFirstRun(meta)) {
    return <FirstRun />
  }

  if (meta && route.environmentKey !== null && !known) {
    return <UnknownEnvironment meta={meta} attempted={route.environmentKey} onPick={switchEnvironment} />
  }

  return (
    <div className="app">
      <header className="top-bar">
        <span className="brand">Nodqora</span>
        <label className="environment-switcher">
          Environment
          <select
            value={environmentKey ?? ''}
            onChange={(event) => switchEnvironment(event.target.value)}
            disabled={!meta}
          >
            {meta?.environments.map((environment) => (
              <option key={environment.key} value={environment.key}>
                {environment.displayName}
              </option>
            ))}
          </select>
        </label>
        {/* ADR-0068: the search control sits beside the switcher, which makes the scope visible at
            the moment it matters — the empty state names the environment and the control that would
            change it is adjacent. */}
        {graph && <SearchBox graph={graph} state={state} onPick={select} />}
        {/* ADR-0081: the chip rests here at all times, so "is everything actually being watched?"
            is answerable on a good day rather than only on a bad one. */}
        {graph && <PluginsChip rosters={rosters} label={label} />}
        <label className="metric-toggle">
          <input type="checkbox" checked={showMetrics} onChange={(e) => setShowMetrics(e.target.checked)} />
          Metrics
        </label>
        {error && <span className="error">{error}</span>}
      </header>

      <div className="workspace">
        <div className="canvas">
          {graph && <Banners banners={banners} label={label} />}
          {!graph ? (
            <p className="loading">Reading the graph…</p>
          ) : graph.nodes.length === 0 ? (
            // ADR-0087: three states, selected by `plugins[]` alone. "No nodes in production" is a
            // false statement during a cold read, and a subline does not retract a headline.
            <EmptyCanvas state={emptyStateOf(graph.plugins)} environmentDisplayName={graph.environment.displayName} />
          ) : (
            <ReactFlowProvider key={graph.environment.key}>
              <Canvas
                graph={graph}
                state={state}
                selectedKey={selectedNode?.key ?? null}
                onSelect={select}
                showMetrics={showMetrics}
                rosters={rosters}
                registry={meta?.plugins ?? []}
                label={label}
              />
            </ReactFlowProvider>
          )}
        </div>

        {graph && selectedNode && (
          <Drawer
            graph={graph}
            node={selectedNode}
            state={selectedState}
            plugins={meta?.plugins ?? []}
            rosters={rosters}
            onSelect={select}
            onClose={() => select(null)}
          />
        )}
        {graph && unresolved !== null && unresolved !== 'SILENT' && route.nodeKey && (
          <UnresolvedDrawer
            state={unresolved}
            environmentDisplayName={graph.environment.displayName}
            parameter={route.nodeKey}
            onClose={() => select(null)}
          />
        )}
      </div>
    </div>
  )
}

/**
 * ADR-0093: an unknown environment key renders a designed not-found that **names the environments
 * that do exist**. Matching stays exact; there is no case-folding and no redirect.
 *
 * Redirecting to a valid key is the worst option, not the safest — it substitutes a scope the user
 * did not ask for and then displays a URL that asserts it. Naming the roster is not the
 * cross-environment surface ADR-0054 called unavailable either: that unavailability is about *graph
 * data*, and the roster is already served by `/api/meta` and already rendered in the switcher.
 */
function UnknownEnvironment({
  meta,
  attempted,
  onPick,
}: {
  meta: Meta
  attempted: string
  onPick: (key: string) => void
}) {
  return (
    <div className="app">
      <header className="top-bar">
        <span className="brand">Nodqora</span>
      </header>
      <div className="not-found">
        <p className="not-found-headline">
          No environment named <code>{attempted}</code>.
        </p>
        <p className="not-found-roster">
          Available:{' '}
          {meta.environments.map((environment, index) => (
            <span key={environment.key}>
              {index > 0 && ', '}
              <button type="button" onClick={() => onPick(environment.key)}>
                {environment.key}
              </button>
            </span>
          ))}
        </p>
      </div>
    </div>
  )
}

/** Polls immediately, then on the server's own interval. */
function usePoll(fetcher: () => Promise<void>, intervalMs: number | null, onError: (message: string) => void) {
  useEffect(() => {
    if (intervalMs === null) return
    let live = true
    const run = () => {
      fetcher().catch((cause: Error) => {
        if (live) onError(cause.message)
      })
    }
    run()
    const timer = setInterval(run, intervalMs)
    return () => {
      live = false
      clearInterval(timer)
    }
  }, [fetcher, intervalMs, onError])
}
