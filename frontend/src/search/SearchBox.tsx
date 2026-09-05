import { useEffect, useMemo, useRef, useState } from 'react'
import { HealthGlyph, healthLabel } from '../canvas/HealthGlyph'
import { foldKey } from '../api/keys'
import { RESULT_LIMIT, noResults, search } from './search'
import type { Graph, Health, State, TypeDescriptor } from '../api/types'

/**
 * ADR-0068: **a ranked result list. The canvas is untouched until the user picks a result.**
 *
 * The control sits in the top chrome beside the environment switcher, which makes the **scope
 * visible at the moment it matters** — ADR-0070's empty state names the environment, and the
 * switcher that would change it is adjacent. `/` focuses it, `Esc` clears and blurs. At most ten
 * rows, with a count line when there are more. There is no minimum query length.
 *
 * **Filter-as-you-type on the canvas was the alternative and it loses on three counts.** Dimming
 * non-matches *is* filtering, and ADR-0018 deferred filter-by-type and filter-by-health wholesale; a
 * live dim delivers the deferred feature under another name, and unlike a filter chip it has no
 * visible off-switch, so an abandoned query leaves the canvas quietly understating what exists. It
 * also collides with ADR-0017 — dimming attacks both the shape and colour channels at once, so a
 * dimmed `UNHEALTHY` node is *less* legible during exactly the incident the fixture's second
 * scenario exists to render. And a dimmed canvas has nowhere to put the "matched via" line, which is
 * ADR-0023's requirement.
 *
 * `Cmd-K` is deliberately unbound: it conventionally opens a command palette and would promise a
 * feature that does not exist.
 */
export function SearchBox({
  graph,
  state,
  onPick,
}: {
  graph: Graph
  state: State | null
  onPick: (key: string) => void
}) {
  const [query, setQuery] = useState('')
  const input = useRef<HTMLInputElement>(null)

  // `/` focuses, from anywhere that is not already a text field.
  useEffect(() => {
    const focus = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (event.key !== '/' || target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA') return
      event.preventDefault()
      input.current?.focus()
    }
    document.addEventListener('keydown', focus)
    return () => document.removeEventListener('keydown', focus)
  }, [])

  const results = useMemo(() => search(query, graph.nodes), [query, graph.nodes])

  const health = useMemo(() => {
    const byKey = new Map<string, Health>()
    state?.nodes.forEach((node) => byKey.set(foldKey(node.nodeKey), node.health))
    return byKey
  }, [state])

  const descriptors = useMemo(
    () => new Map<string, TypeDescriptor>(graph.typeDescriptors.map((d) => [d.type, d])),
    [graph.typeDescriptors],
  )

  // ADR-0069: the query and its results clear on pick, so a stale result list never overlays a
  // canvas the user is now reading. Re-running the search costs one keystroke.
  const pick = (key: string) => {
    setQuery('')
    input.current?.blur()
    onPick(key)
  }

  return (
    <div className="search">
      <input
        ref={input}
        type="search"
        className="search-input"
        placeholder="Search nodes  /"
        aria-label="Search nodes"
        value={query}
        onChange={(event) => setQuery(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            setQuery('')
            event.currentTarget.blur()
          }
          if (event.key === 'Enter' && results[0]) pick(results[0].node.key)
        }}
      />

      {query !== '' && (
        <div className="search-results" role="listbox" aria-label="Search results">
          {results.length === 0 ? (
            // ADR-0070: the empty state names the environment and offers nothing further.
            <p className="empty search-empty">{noResults(graph.environment.displayName, query)}</p>
          ) : (
            <>
              <ul>
                {results.slice(0, RESULT_LIMIT).map((result) => (
                  <li key={result.node.key}>
                    <button type="button" onClick={() => pick(result.node.key)}>
                      <span className="search-name">
                        {/* ADR-0058: rows fall back to `key` rather than fabricating a name. On the
                            fixture that is most nodes — `kubernetes` emits a null `displayName`
                            deliberately, to avoid manufacturing a merge conflict with YAML. */}
                        {result.node.displayName ?? result.node.key}
                      </span>
                      <HealthGlyph health={health.get(foldKey(result.node.key)) ?? 'UNKNOWN'} />
                      <span className="rel">
                        {result.node.type
                          ? (descriptors.get(result.node.type)?.label ?? result.node.type)
                          : 'Untyped'}
                        {' · '}
                        {healthLabel(health.get(foldKey(result.node.key)) ?? 'UNKNOWN')}
                      </span>
                      {/* ADR-0023 discharged: a hit whose matched string the node does not display
                          no longer looks like a bug. */}
                      {result.matchedVia && (
                        <span className="search-via">
                          via {result.matchedVia.kind} <code>{result.matchedVia.reference}</code>
                        </span>
                      )}
                    </button>
                  </li>
                ))}
              </ul>
              {/* The cap is what keeps ADR-0067's total order honest: it is deterministic over
                  thirty substring hits but only *informative* at the top, and the count line tells
                  the user the query was too broad where a silently-truncated list would not. */}
              {results.length > RESULT_LIMIT && (
                <p className="search-count">
                  {RESULT_LIMIT} of {results.length} matches
                </p>
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}
