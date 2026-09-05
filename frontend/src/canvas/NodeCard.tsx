// SPDX-License-Identifier: Apache-2.0
import { Handle, Position, type NodeProps } from '@xyflow/react'
import { HealthGlyph } from './HealthGlyph'
import { TypeIcon } from './TypeIcon'
import type { Health, TypeDescriptor } from '../api/types'

/**
 * ADR-0018's node rendering: type icon, display name, type label, health, and one optional metric
 * line behind a canvas-level toggle.
 *
 * No per-type template. The core never branches on `type` (ADR-0001) and neither does this — a Kafka
 * topic and a service differ only in which descriptor they resolve to.
 *
 * ADR-0018 was **amended by ADR-0083** to add two **conditional outcome marks**. They are
 * conditional by construction: neither can fire while every plugin's `outcome` is `COMPLETE`, so an
 * ordinary day renders exactly the list above.
 *
 * | condition | mark |
 * |---|---|
 * | any source retained (ADR-0084) | hatched top edge |
 * | any health-capable backing abstained | outlined border plus a footer token, e.g. `connect blind` |
 *
 * Neither is a colour (ADR-0082): health keeps the hue channel to itself, so ADR-0017's
 * colour-blind argument is not diluted by a second colour-coded axis fighting it for attention.
 *
 * The marks are what make `DISABLED` and an unwatched node distinguishable **on the canvas**. Under
 * ADR-0017 alone, an honestly-`DISABLED` enricher and two connectors reading `HEALTHY` because
 * `connect` is unreachable all render quiet; here the enricher stays calm and unmarked while the
 * connectors carry a border and a named plugin.
 */

export interface NodeCardData extends Record<string, unknown> {
  nodeKey: string
  displayName: string | null
  descriptor: TypeDescriptor | undefined
  health: Health
  metricLine: string | null
  showMetrics: boolean
  emphasis: 'none' | 'selected' | 'upstream' | 'downstream' | 'dimmed'
  /** Plugin labels whose view of this node's topology was carried forward rather than confirmed. */
  retained: string[]
  /** Plugin labels that back this node and did not observe it this cycle. */
  blind: string[]
}

export function NodeCard({ data }: NodeProps) {
  const card = data as NodeCardData
  const classes = ['node-card', `node-card-${card.emphasis}`]
  if (card.retained.length > 0) classes.push('node-card-retained')
  if (card.blind.length > 0) classes.push('node-card-blind')

  return (
    <div
      className={classes.join(' ')}
      title={
        card.retained.length > 0
          ? `${card.nodeKey} — topology retained from ${card.retained.join(', ')}`
          : card.nodeKey
      }
    >
      <Handle type="target" position={Position.Left} />
      <div className="node-card-head">
        <TypeIcon icon={card.descriptor?.icon ?? null} />
        {/* ADR-0058: the server never invents a name, so the `?? key` fallback lives here. */}
        <span className="node-card-name">{card.displayName ?? card.nodeKey}</span>
        <HealthGlyph health={card.health} />
      </div>
      <div className="node-card-type">
        {/* An unregistered type resolves to the fallback descriptor and still renders. */}
        {card.descriptor?.label ?? card.descriptor?.type ?? 'Unknown type'}
      </div>
      {/* The line is clamped to one line in CSS, because Canvas.tsx declares the height of one and
          a node observed by three plugins composes five metrics. `title` is where the rest goes on
          the canvas; section 2 of the drawer has them all, namespaced by plugin. */}
      {card.showMetrics && card.metricLine && (
        <div className="node-card-metric" title={card.metricLine}>
          {card.metricLine}
        </div>
      )}
      {/* ADR-0083: during trouble, which plugin went blind is worth more than which plugins
          discovered the node, and the full source list is one click away in section 7. */}
      {card.blind.length > 0 && (
        <div className="node-card-blind-token">{card.blind.map((plugin) => `${plugin} blind`).join(' · ')}</div>
      )}
      <Handle type="source" position={Position.Right} />
    </div>
  )
}
