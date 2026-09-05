import { foldKey } from '../api/keys'
import type { GraphNode } from '../api/types'

/**
 * ADR-0066 and ADR-0067: case-folded substring matching over the **identifying string set only**,
 * with a total rank order and no fuzzy tier.
 *
 * The reference pipeline's own list of hard cases says of `payments-enricher` and
 * `payments-events-v1` that *"search must disambiguate, not just substring-match"*. That brief is
 * wrong, and the correction is the decision: `payments-e` matches both names and **both are correct
 * hits**. No matcher separates them, because there is nothing to separate — the user has typed a
 * prefix that two nodes genuinely share. Disambiguation is the result list's job (ADR-0068). What
 * the matcher owes is determinism and ordering, not fewer results.
 *
 * **Searchable strings are exactly `key`, `displayName` and `backings[].reference` — no wider.**
 * Widening to `ownerKey` or `type` is not search; it is the filter set ADR-0018 deferred, arriving
 * through the search box without being designed. The cost is recorded rather than mitigated: an SRE
 * who knows the team but not the node gets nothing here.
 *
 * **A backing reference is searchable both whole and by its `/`-separated segments**, because the
 * SRE's clipboard holds `enricher-v2`, not `payments-prod/enricher-v2` — the string arrives from a
 * Kubernetes alert. That partially defeats the paragraph above and is accepted: splitting makes
 * `payments-prod` a token on every Kubernetes-backed node, so typing it returns the whole namespace,
 * which is owner-filtering by the back door. Suppressing it would require this file to know which
 * segment of a plugin's reference is a namespace, and ADR-0015 forbids the frontend knowing plugin
 * shapes. It is a known leak, not a bug to be fixed here.
 *
 * No fuzzy or subsequence tier. ADR-0021's ban on one protected identity *resolution*, where nothing
 * human intervenes, so it does not transfer — here a person picks from the results. Fuzzy is
 * declined on scale instead: subsequence matching is invisible across ten fixture nodes and returns
 * near-everything for short queries on the two-hundred-connector cluster ADR-0036 permits.
 */

/**
 * ADR-0067's total order. Lower ranks first; ties break alphabetically by `key`.
 *
 * The order must be **total**, not best-effort, for the reason ADR-0050 forced canonical collection
 * ordering one layer down: an unstable order means the same query surfaces a different top hit on
 * different renders.
 */
export const enum Rank {
  ExactKey = 0,
  PrefixOfName = 1,
  SubstringOfName = 2,
  BackingSegment = 3,
  WholeBackingReference = 4,
}

export interface SearchResult {
  node: GraphNode
  rank: Rank
  /**
   * ADR-0068's "matched via" line, present **only** when the hit came from a backing rather than
   * from `key` or `displayName`. It is ADR-0023's requirement discharged: a hit on `enricher-v2`
   * reads "`payments-enricher`, via Deployment `enricher-v2`", so a result whose matched string the
   * node does not display no longer looks like a bug.
   */
  matchedVia: { kind: string; reference: string } | null
}

/** ADR-0068: at most ten rows are shown, with a count line when there are more. */
export const RESULT_LIMIT = 10

/**
 * @returns every match, ranked. The caller renders the first {@link RESULT_LIMIT} and the total.
 *
 * There is **no minimum query length** (ADR-0068). An empty query matches nothing rather than
 * everything — it is the resting state of the box, not a request for the whole environment.
 */
export function search(query: string, nodes: GraphNode[]): SearchResult[] {
  // Case-folded, following ADR-0020, which already folds keys for uniqueness and merge. A
  // case-sensitive search over a case-insensitive identity model would let a user type a string
  // that *is* the node key and get nothing.
  const needle = foldKey(query)
  if (needle === '') return []

  const results: SearchResult[] = []
  for (const node of nodes) {
    const hit = rank(needle, node)
    if (hit !== null) results.push(hit)
  }

  return results.sort((a, b) => a.rank - b.rank || foldKey(a.node.key).localeCompare(foldKey(b.node.key)))
}

function rank(needle: string, node: GraphNode): SearchResult | null {
  const key = foldKey(node.key)
  const displayName = node.displayName === null ? null : foldKey(node.displayName)
  const names = displayName === null ? [key] : [key, displayName]

  if (key === needle) return { node, rank: Rank.ExactKey, matchedVia: null }
  if (names.some((name) => name.startsWith(needle))) {
    return { node, rank: Rank.PrefixOfName, matchedVia: null }
  }
  if (names.some((name) => name.includes(needle))) {
    return { node, rank: Rank.SubstringOfName, matchedVia: null }
  }

  // A backing hit reports *which* backing matched, so the row can show the string the node does not
  // display. Segments outrank the whole reference: prefix-anchoring was available once segments
  // existed and was still rejected — `enricher` would find `enricher-v2` through the segment, but
  // `v2` would then find nothing at all.
  for (const backing of node.backings) {
    if (foldKey(backing.reference).split('/').some((segment) => segment.includes(needle))) {
      return { node, rank: Rank.BackingSegment, matchedVia: { kind: backing.kind, reference: backing.reference } }
    }
  }
  for (const backing of node.backings) {
    if (foldKey(backing.reference).includes(needle)) {
      return {
        node,
        rank: Rank.WholeBackingReference,
        matchedVia: { kind: backing.kind, reference: backing.reference },
      }
    }
  }

  return null
}

/**
 * ADR-0070: **the empty state names the environment** and offers nothing further.
 *
 * "No results" and "no such node anywhere" otherwise render as the same pixels, and an undesigned
 * empty state discards the scope information for free. Zero results is not an edge case here —
 * staging is missing three of production's ten nodes, so an SRE scoped to staging who searches
 * `trino` correctly concludes "no such node" about a node that exists one environment over. That is
 * ADR-0054's price, paid at the point of use, and naming the environment is the whole of the
 * available mitigation.
 *
 * A "try production?" affordance is declined twice over: it needs the cross-environment surface
 * ADR-0054 called *unavailable*, and a control that switches environment from inside a search result
 * breaks ADR-0018's "environment is a scope, not a filter" — the scope is the frame every reading is
 * made in, and search must not move it out from under the reader.
 */
export const noResults = (environmentDisplayName: string, query: string) =>
  `No node in ${environmentDisplayName} matches “${query}”.`
