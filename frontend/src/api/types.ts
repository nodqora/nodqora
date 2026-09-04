/**
 * The wire shapes of ADR-0053's three GETs.
 *
 * `null` crosses the wire unresolved (ADR-0058), so every optional scalar is `| null` rather than
 * optional. The `?? key` fallback lives here in the frontend, in the two places that draw a name —
 * that is the whole cost of the server never inventing a value no plugin supplied.
 */

export type Health = 'HEALTHY' | 'DEGRADED' | 'UNHEALTHY' | 'UNKNOWN' | 'DISABLED'

export type OutcomeStatus = 'COMPLETE' | 'PARTIAL' | 'FAILED'

export interface EnvironmentRef {
  key: string
  displayName: string
}

export interface Meta {
  environments: EnvironmentRef[]
  plugins: PluginRef[]
  refresh: { graphSeconds: number; stateSeconds: number }
}

export interface PluginRef {
  id: string
  displayLabel: string
  capabilities: string[]
}

export interface Link {
  rel: string
  label: string | null
  url: string
}

export interface Backing {
  plugin: string
  kind: string
  reference: string
}

export interface Source {
  plugin: string
  confirmedAt: string | null
}

export interface GraphNode {
  key: string
  type: string | null
  displayName: string | null
  description: string | null
  ownerKey: string | null
  links: Link[]
  backings: Backing[]
  metadata: Record<string, Record<string, unknown>>
  sources: Source[]
  discoveredAt: string
  updatedAt: string
}

export interface GraphEdge {
  fromKey: string
  toKey: string
  relation: string
  metadata: Record<string, Record<string, unknown>>
  sources: string[]
  discoveredAt: string
  updatedAt: string
}

export interface Owner {
  key: string
  displayName: string | null
  channel: string | null
  onCall: string | null
}

export interface TypeDescriptor {
  type: string
  label: string | null
  category: string | null
  icon: string | null
  source: string
}

/**
 * ADR-0002: orientation is carried for completeness, but phrasing is selected by which *end* of the
 * stored edge you are reading from — `from` takes `forwardPhrasing`, `to` takes `reversePhrasing`.
 * Traversal never consults it: downstream is always "follow outgoing".
 */
export interface RelationDescriptor {
  relation: string
  orientation: 'FORWARD' | 'REVERSED'
  forwardPhrasing: string
  reversePhrasing: string
}

/** ADR-0085: `outcome` and `recordedAt` are null for a pair that has never reported. */
export interface PluginOutcome {
  plugin: string
  capability: string
  outcome: OutcomeStatus | null
  reasons: string[]
  recordedAt: string | null
}

export interface Graph {
  environment: EnvironmentRef
  nodes: GraphNode[]
  edges: GraphEdge[]
  owners: Owner[]
  typeDescriptors: TypeDescriptor[]
  relationDescriptors: RelationDescriptor[]
  plugins: PluginOutcome[]
}

export interface NodeState {
  nodeKey: string
  health: Health
  rawSignal: string | null
  metrics: Record<string, Record<string, unknown>>
  observedAt: string | null
}

export interface State {
  environment: string
  observedAt: string | null
  nodes: NodeState[]
  plugins: PluginOutcome[]
}
