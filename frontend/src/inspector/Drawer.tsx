import { HealthGlyph, healthLabel } from '../canvas/HealthGlyph'
import { connectionsOf } from './connections'
import { sameKey } from '../api/keys'
import { healthCaveat, retained } from '../outcome/marks'
import { labeller, type Rosters } from '../outcome/plugins'
import type { Graph, GraphNode, NodeState, PluginRef } from '../api/types'

/**
 * ADR-0019: a right drawer, fixed width, one scrolling column, with a **fixed section order for
 * every node type**.
 *
 * Sections are not per-type templates. A Kafka topic and a service differ only in which sections have
 * content — which drops a per-type branch from the frontend, consistent with the core never branching
 * on `type`.
 *
 * **Every section renders a designed empty state rather than disappearing.** Absence is information:
 * "No owner recorded. Nobody to page." beats a missing section, which is indistinguishable from a
 * section that failed to load. The fixture supplies both extremes on purpose — one node with every
 * ownership field and eight links, one with a name, a type and nothing else — and both must render.
 *
 * The known cost: this takes about a fifth of the width from a layout that is already width-hungry
 * and height-light. That is ADR-0019's own recorded cost and is not to be defended against here.
 */
export function Drawer({
  graph,
  node,
  state,
  plugins,
  rosters,
  onSelect,
  onClose,
}: {
  graph: Graph
  node: GraphNode
  state: NodeState | null
  plugins: PluginRef[]
  rosters: Rosters
  onSelect: (key: string) => void
  onClose: () => void
}) {
  // ADR-0020's folding, all of it. Comparing only the case would leave a padded key resolving to
  // no Owner, which ADR-0048 makes an *ordinary* state — so the bug renders as an empty contact
  // section rather than as an error.
  const owner = graph.owners.find((candidate) => sameKey(candidate.key, node.ownerKey))
  const descriptor = node.type ? graph.typeDescriptors.find((d) => d.type === node.type) : undefined
  const connections = connectionsOf(node.key, graph.edges, graph.relationDescriptors)
  const health = state?.health ?? 'UNKNOWN'
  const labelOf = labeller(plugins)
  // ADR-0083: the caveat is a statement *about that health value*, so it sits inside section 2.
  const caveat = healthCaveat(node, rosters, plugins, labelOf, state?.observedAt != null)

  return (
    <aside className="drawer" aria-label="Node inspector">
      <header className="drawer-head">
        <div>
          <h2>{node.displayName ?? node.key}</h2>
          <code className="drawer-key">{node.key}</code>
        </div>
        <button className="drawer-close" onClick={onClose} aria-label="Close inspector">
          &times;
        </button>
      </header>

      {/* 1. Identity */}
      <section className="drawer-identity">
        <span className="chip">
          <HealthGlyph health={health} />
          {healthLabel(health)}
        </span>
        <span className="chip chip-quiet">{descriptor?.label ?? node.type ?? 'Untyped'}</span>
        <span className="chip chip-quiet">{graph.environment.displayName}</span>
      </section>
      {node.description && <p className="drawer-description">{node.description}</p>}

      {/* 2. Health. Raw signal is always displayed, so the inspector can always show its work: a
          normalized DEGRADED is never presented without the string it came from. */}
      <Section title="Health">
        {state === null ? (
          <Empty>Health has not been read yet.</Empty>
        ) : (
          <>
            <dl className="pairs">
              <dt>Normalized</dt>
              <dd>{healthLabel(health)}</dd>
              <dt>Raw signal</dt>
              <dd>
                {state.rawSignal ?? (
                  <Empty inline>
                    Nothing observes this node. No plugin carries a backing of its own here.
                  </Empty>
                )}
              </dd>
              <dt>Observed</dt>
              <dd>{state.observedAt ? new Date(state.observedAt).toLocaleString() : <Empty inline>Never</Empty>}</dd>
            </dl>
            <Namespaced values={state.metrics} labelOf={labelOf} empty="No metrics recorded." />
          </>
        )}
        {/* ADR-0083: the caveat names the plugin that could not look and what the displayed value
            was actually composed from. A separate Observation section was rejected — putting it
            elsewhere makes the reader correlate two places to find out whether the number above is
            trustworthy, and ADR-0019's whole argument for a fixed section order is that the reader
            should not have to hunt. */}
        {caveat && <p className="caveat">{caveat}</p>}
      </Section>

      {/* 3. Connections — each peer a control that reselects. */}
      <Section title="Connections">
        {connections.upstream.length === 0 && connections.downstream.length === 0 ? (
          <Empty>Nothing connects to this node.</Empty>
        ) : (
          <>
            <Peers direction="upstream" connections={connections.upstream} onSelect={onSelect} subject={node} />
            <Peers direction="downstream" connections={connections.downstream} onSelect={onSelect} subject={node} />
          </>
        )}
      </Section>

      {/* 4. Ownership */}
      <Section title="Ownership">
        {node.ownerKey === null ? (
          <Empty>No owner recorded. Nobody to page.</Empty>
        ) : (
          <dl className="pairs">
            <dt>Team</dt>
            <dd>{owner?.displayName ?? node.ownerKey}</dd>
            <dt>Channel</dt>
            {/* ADR-0048: an ownerKey resolving to no Owner is ordinary, not an error — it is the
                expected steady state where manifests are annotated before anyone writes the YAML
                owner block. It renders as a name with an empty contact section. */}
            <dd>{owner?.channel ?? <Empty inline>No contact details recorded</Empty>}</dd>
            <dt>On call</dt>
            <dd>{owner?.onCall ?? <Empty inline>Not recorded</Empty>}</dd>
          </dl>
        )}
      </Section>

      {/* 5. Go to */}
      <Section title="Go to">
        {node.links.length === 0 ? (
          <Empty>No links recorded.</Empty>
        ) : (
          <ul className="links">
            {node.links.map((link) => (
              <li key={`${link.rel} ${link.url}`}>
                <a href={link.url} target="_blank" rel="noreferrer">
                  {link.label ?? link.rel}
                </a>
                <span className="rel">{link.rel}</span>
              </li>
            ))}
          </ul>
        )}
      </Section>

      {/* 6. Backings — also the alternate-name index (ADR-0023): there is no `aliases` field, and
          this is where `enricher-v2` and `enrich-consumer-prod` live. */}
      <Section title="Backings">
        {node.backings.length === 0 ? (
          <Empty>No physical objects behind this node. It is declared, not discovered.</Empty>
        ) : (
          <ul className="backings">
            {node.backings.map((backing) => (
              <li key={`${backing.plugin} ${backing.kind} ${backing.reference}`}>
                <code>{backing.reference}</code>
                <span className="rel">
                  {backing.kind} · {labelOf(backing.plugin)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>

      {/* 7. Metadata, then Discovered by. */}
      <Section title="Metadata">
        <Namespaced values={node.metadata} labelOf={labelOf} empty="No plugin metadata recorded." />
      </Section>

      {/* ADR-0083: section 7 carries the freshness, one row per source — plugin, when it was last
          confirmed, and a `retained` tag where ADR-0084 says so. That is the entirety of
          `sources[]`'s new width on the wire, rendered where `sources[]` already rendered. */}
      <Section title="Discovered by">
        <ul className="sources">
          {node.sources.map((source) => (
            <li key={source.plugin}>
              {labelOf(source.plugin)}
              {/* ADR-0084: retention is not a duration, so the UI never claims one. The elapsed
                  time is information and the tag is the judgement, and the two are computed
                  differently on purpose — a plugin whose poll interval is long shows a large
                  elapsed time with no tag, which is correct and was the failure mode of every
                  threshold considered. */}
              {retained(source, rosters.discovery) && <span className="tag-retained">retained</span>}
              {/* ADR-0056: when this plugin's snapshot last actually carried this key — which is
                  what tells "confirmed 30 seconds ago" from "retained since Tuesday". */}
              <span className="rel">
                {source.confirmedAt ? `confirmed ${new Date(source.confirmedAt).toLocaleString()}` : 'confirmed —'}
              </span>
            </li>
          ))}
        </ul>
        <p className="note">
          Last topology change {new Date(node.updatedAt).toLocaleString()}; first seen{' '}
          {new Date(node.discoveredAt).toLocaleString()}.
        </p>
      </Section>
    </aside>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="drawer-section">
      <h3>{title}</h3>
      {children}
    </section>
  )
}

function Empty({ children, inline }: { children: React.ReactNode; inline?: boolean }) {
  return <span className={inline ? 'empty empty-inline' : 'empty'}>{children}</span>
}

const PEERS = {
  upstream: { heading: 'Upstream', empty: 'Nothing feeds this node.' },
  downstream: { heading: 'Downstream', empty: 'Nothing reads from this node.' },
} as const

function Peers({
  direction,
  connections,
  onSelect,
  subject,
}: {
  direction: keyof typeof PEERS
  connections: ReturnType<typeof connectionsOf>['upstream']
  onSelect: (key: string) => void
  subject: GraphNode
}) {
  return (
    <div className="peers">
      <h4>{PEERS[direction].heading}</h4>
      {connections.length === 0 ? (
        <Empty>{PEERS[direction].empty}</Empty>
      ) : (
        <ul>
          {connections.map((connection) => (
            <li key={`${connection.relation} ${connection.peerKey}`}>
              <button onClick={() => onSelect(connection.peerKey)}>{connection.peerKey}</button>
              {/* Phrased from this node's end, so the same stored edge reads correctly from both. */}
              <span className="rel">
                {subject.displayName ?? subject.key} {connection.phrasing} it
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** ADR-0006: rendered generically, per plugin namespace. The core never looks inside. */
function Namespaced({
  values,
  labelOf,
  empty,
}: {
  values: Record<string, Record<string, unknown>>
  labelOf: (pluginId: string) => string
  empty: string
}) {
  const namespaces = Object.entries(values).sort(([a], [b]) => a.localeCompare(b))
  if (namespaces.length === 0) return <Empty>{empty}</Empty>
  return (
    <>
      {namespaces.map(([pluginId, entries]) => (
        <div key={pluginId} className="namespace">
          <h4>{labelOf(pluginId)}</h4>
          <dl className="pairs">
            {Object.entries(entries)
              .sort(([a], [b]) => a.localeCompare(b))
              .flatMap(([name, value]) => [
                <dt key={`${name}-k`}>{name}</dt>,
                <dd key={`${name}-v`}>{String(value)}</dd>,
              ])}
          </dl>
        </div>
      ))}
    </>
  )
}
