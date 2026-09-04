import { useCallback, useEffect, useMemo, useState } from 'react'
import { ReactFlowProvider } from '@xyflow/react'
import { api } from './api/client'
import { Canvas } from './canvas/Canvas'
import { Drawer } from './inspector/Drawer'
import { sameKey } from './api/keys'
import type { Graph, Meta, State } from './api/types'


export function App() {
  const [meta, setMeta] = useState<Meta | null>(null)
  const [graph, setGraph] = useState<Graph | null>(null)
  const [state, setState] = useState<State | null>(null)
  const [environmentKey, setEnvironmentKey] = useState<string | null>(null)
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  // ADR-0018: metrics are opt-in, default on, so users can drop the overlay to avoid clutter.
  const [showMetrics, setShowMetrics] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api
      .meta()
      .then((fetched) => {
        setMeta(fetched)
        setEnvironmentKey((current) => current ?? fetched.environments[0]?.key ?? null)
      })
      .catch((cause: Error) => setError(cause.message))
  }, [])

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

  // ADR-0004: environment is a scope, not a filter. Switching it changes the graph, so the previous
  // selection is not carried across by key — a node absent from an environment has no row at all.
  const switchEnvironment = (key: string) => {
    setEnvironmentKey(key)
    setGraph(null)
    setState(null)
    setSelectedKey(null)
  }

  const selectedNode = useMemo(
    () => graph?.nodes.find((node) => sameKey(node.key, selectedKey)) ?? null,
    [graph, selectedKey],
  )
  const selectedState = useMemo(
    () => state?.nodes.find((node) => sameKey(node.nodeKey, selectedKey)) ?? null,
    [state, selectedKey],
  )

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
        <label className="metric-toggle">
          <input type="checkbox" checked={showMetrics} onChange={(e) => setShowMetrics(e.target.checked)} />
          Metrics
        </label>
        {error && <span className="error">{error}</span>}
      </header>

      <div className="workspace">
        <div className="canvas">
          {graph ? (
            <ReactFlowProvider key={graph.environment.key}>
              <Canvas
                graph={graph}
                state={state}
                selectedKey={selectedKey}
                onSelect={setSelectedKey}
                showMetrics={showMetrics}
              />
            </ReactFlowProvider>
          ) : (
            <p className="loading">Reading the graph…</p>
          )}
        </div>

        {graph && selectedNode && (
          <Drawer
            graph={graph}
            node={selectedNode}
            state={selectedState}
            plugins={meta?.plugins ?? []}
            onSelect={setSelectedKey}
            onClose={() => setSelectedKey(null)}
          />
        )}
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
