import { Handle, Position, type NodeProps } from '@xyflow/react'
import { HealthGlyph } from './HealthGlyph'
import { TypeIcon } from './TypeIcon'
import type { Health, TypeDescriptor } from '../api/types'

/**
 * ADR-0018's node rendering, and nothing beyond it: type icon, display name, type label, health, and
 * one optional metric line behind a canvas-level toggle.
 *
 * No per-type template. The core never branches on `type` (ADR-0001) and neither does this — a Kafka
 * topic and a service differ only in which descriptor they resolve to.
 */

export interface NodeCardData extends Record<string, unknown> {
  nodeKey: string
  displayName: string | null
  descriptor: TypeDescriptor | undefined
  health: Health
  metricLine: string | null
  showMetrics: boolean
  emphasis: 'none' | 'selected' | 'upstream' | 'downstream' | 'dimmed'
}

export function NodeCard({ data }: NodeProps) {
  const card = data as NodeCardData

  return (
    <div className={`node-card node-card-${card.emphasis}`} title={card.nodeKey}>
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
      {card.showMetrics && card.metricLine && <div className="node-card-metric">{card.metricLine}</div>}
      <Handle type="source" position={Position.Right} />
    </div>
  )
}
