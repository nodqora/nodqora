import { foldKey } from '../api/keys'
import type { GraphEdge, RelationDescriptor } from '../api/types'

/**
 * ADR-0019's Connections section: Upstream and Downstream, each peer a control that reselects.
 *
 * Phrasing goes through the RelationDescriptor (ADR-0002), so the API never serves pre-phrased edge
 * text. The rule is simply **which end you are reading from**: the `from` end takes
 * `forwardPhrasing`, the `to` end takes `reversePhrasing`. That is what makes one stored edge read
 * "delivers to" from the topic and "consumes from" from the service, with no branch on orientation
 * and none on relation.
 *
 * Raw `from RELATION to` would be wrong for a third of the fixture's edges, which is why this is not
 * optional presentation polish.
 */


export interface Connection {
  /** The node at the other end. */
  peerKey: string
  phrasing: string
  relation: string
}

export interface Connections {
  downstream: Connection[]
  upstream: Connection[]
}

export function connectionsOf(
  nodeKey: string,
  edges: GraphEdge[],
  descriptors: RelationDescriptor[],
): Connections {
  const byRelation = new Map(descriptors.map((descriptor) => [descriptor.relation, descriptor]))
  const self = foldKey(nodeKey)

  const downstream: Connection[] = []
  const upstream: Connection[] = []

  for (const edge of edges) {
    const descriptor = byRelation.get(edge.relation)
    if (foldKey(edge.fromKey) === self) {
      downstream.push({
        peerKey: edge.toKey,
        // An unregistered relation still renders, exactly as an unregistered type does (ADR-0001).
        phrasing: descriptor?.forwardPhrasing ?? edge.relation,
        relation: edge.relation,
      })
    } else if (foldKey(edge.toKey) === self) {
      upstream.push({
        peerKey: edge.fromKey,
        phrasing: descriptor?.reversePhrasing ?? edge.relation,
        relation: edge.relation,
      })
    }
  }

  const byPeer = (a: Connection, b: Connection) => a.peerKey.localeCompare(b.peerKey)
  return { downstream: downstream.sort(byPeer), upstream: upstream.sort(byPeer) }
}
