// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { DRAW_EDGES_DOC, drawEdgesDocUrl, edgelessSentence, isEdgelessByRoster } from './edgeless'
import type { Graph, GraphEdge, PluginOutcome } from '../api/types'

const production = JSON.parse(readFileSync('../fixtures/golden/graph-production.json', 'utf8')) as Graph

const pair = (plugin: string): PluginOutcome => ({
  plugin,
  capability: 'DISCOVERY',
  outcome: 'COMPLETE',
  reasons: [],
  recordedAt: null,
})

const graphOf = (plugins: string[], edges: GraphEdge[] = []) => ({
  nodes: production.nodes,
  edges,
  plugins: plugins.map(pair),
})

describe('a canvas with nodes and no edges says where edges come from', () => {
  it('fires on a kubernetes-only environment that has nodes and no edges', () => {
    expect(isEdgelessByRoster(graphOf(['kubernetes']))).toBe(true)
  })

  it('does not fire when yaml is declared and has written no edges yet', () => {
    // #113: keyed on the roster, not on edges.length alone. An environment that declares `yaml` has
    // already taken the step the sentence points at, so "edges come from a topology file" would be
    // wrong about the cause.
    expect(isEdgelessByRoster(graphOf(['kubernetes', 'yaml']))).toBe(false)
  })

  it('does not fire when kafka is declared, even with no edges', () => {
    expect(isEdgelessByRoster(graphOf(['kubernetes', 'kafka']))).toBe(false)
  })

  it('does not fire when connect is declared, even with no edges', () => {
    expect(isEdgelessByRoster(graphOf(['kubernetes', 'connect']))).toBe(false)
  })

  it('does not fire once any edge is on the canvas', () => {
    expect(isEdgelessByRoster(graphOf(['kubernetes'], production.edges))).toBe(false)
  })

  it('does not fire on an empty canvas, which ADR-0087 already answers', () => {
    // Not a fourth `CanvasEmptyState`: with no nodes the canvas is empty, and the empty state is the
    // whole answer. Printing both would be two sentences about one screen.
    expect(isEdgelessByRoster({ ...graphOf(['kubernetes']), nodes: [] })).toBe(false)
  })

  it('names the environment and never states or implies a time', () => {
    // ADR-0087's rule for these sentences. The fix is a file the operator writes, not a wait.
    const sentence = edgelessSentence('Homelab')

    expect(sentence).toContain('Homelab')
    expect(sentence).toContain('topology directory')
    expect(sentence).not.toContain('topology file')
    expect(sentence).not.toMatch(/minute|second|soon|shortly|wait|refresh|\d/i)
  })
})

describe('the draw-the-edges link resolves in the tree it was built from', () => {
  it('points a released build at its own tag', () => {
    expect(drawEdgesDocUrl('0.2.0')).toBe(
      'https://github.com/nodqora/nodqora/blob/v0.2.0/docs/install.md#5-draw-the-edges',
    )
  })

  it('names a heading that is actually in the document', () => {
    // ADR-0161: the link ships only when its page resolves. The page and this constant are in the
    // same tree, so the anchor is checked against the file rather than against a string — renaming
    // the step fails here instead of in a reader's browser.
    const doc = readFileSync(`../${DRAW_EDGES_DOC}`, 'utf8')

    expect(doc).toMatch(/^## 5\. Draw the edges$/m)
  })
})
