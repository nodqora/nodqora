import type { Health } from '../api/types'

/**
 * ADR-0017: every surface that renders health pairs its colour token with a **distinct glyph shape**,
 * and the glyph is the primary encoding.
 *
 * Colour alone fails twice. `UNKNOWN` and `DISABLED` have no natural hue — one because nothing
 * observes the node, the other because someone deliberately turned it off — so any honest palette
 * renders both as muted greys and they collapse into each other. And red/green carries no
 * information for a substantial fraction of viewers, which for a tool whose entire job is
 * at-a-glance operational state is a defect rather than a polish item.
 *
 * `UNKNOWN` is styled **calm, not alarming**: it is a resting state rather than an error one, and
 * four of the fixture's ten production nodes hold it permanently because nothing will ever observe
 * them. A node nobody is watching is not a node in trouble.
 */

const GLYPHS: Record<Health, { path: JSX.Element; label: string }> = {
  HEALTHY: {
    label: 'Healthy',
    path: <circle cx="8" cy="8" r="5" />,
  },
  DEGRADED: {
    label: 'Degraded',
    path: <polygon points="8,2.5 14,13 2,13" />,
  },
  UNHEALTHY: {
    label: 'Unhealthy',
    path: <polygon points="5.4,2.5 10.6,2.5 13.5,5.4 13.5,10.6 10.6,13.5 5.4,13.5 2.5,10.6 2.5,5.4" />,
  },
  DISABLED: {
    label: 'Disabled',
    path: (
      <>
        <rect x="4" y="3" width="3" height="10" rx="0.6" />
        <rect x="9" y="3" width="3" height="10" rx="0.6" />
      </>
    ),
  },
  UNKNOWN: {
    label: 'Unknown',
    // Dashed and hollow: nothing is observing this node, which is not the same as nothing being
    // wrong with it, and neither is an alarm.
    path: <circle cx="8" cy="8" r="5" fill="none" strokeDasharray="2.6 2.2" strokeWidth="1.6" />,
  },
}

export function HealthGlyph({ health, title }: { health: Health; title?: string }) {
  const glyph = GLYPHS[health]
  return (
    <svg
      className={`glyph glyph-${health.toLowerCase()}`}
      viewBox="0 0 16 16"
      width="16"
      height="16"
      role="img"
      aria-label={glyph.label}
    >
      <title>{title ?? glyph.label}</title>
      {glyph.path}
    </svg>
  )
}

export function healthLabel(health: Health) {
  return GLYPHS[health].label
}
