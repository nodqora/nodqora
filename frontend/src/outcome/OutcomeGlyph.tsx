// SPDX-License-Identifier: Apache-2.0
import type { OutcomeStatus } from '../api/types'

/**
 * ADR-0082: **`outcome` is drawn as a square family in monochrome ink, never a hue.**
 *
 * ADR-0081 puts `outcome` on the same screens as `health`. They are different questions (ADR-0026)
 * and must not be mistakeable for one another — a `PARTIAL` plugin read as a `DEGRADED` node is
 * worse than showing neither.
 *
 * The colour channel is already fully spent: ADR-0017 assigns five health values five colours, and
 * the two with no natural hue — `UNKNOWN` and `DISABLED` — are exactly the muted greys a fourth
 * channel would reach for. The shape channel is spent too, and the **square is the one primitive
 * ADR-0017's five shapes leave free** — circle, triangle, octagon, double bar and dashed ring are
 * all taken. So an outcome glyph cannot be read as a health glyph even at 9px, in a screenshot, or
 * in monochrome.
 *
 * | outcome | glyph |
 * |---|---|
 * | `COMPLETE` | filled rounded square |
 * | `PARTIAL` | outlined square, hatched |
 * | `FAILED` | outlined square, crossed |
 * | unreported (`null`) | outlined square, empty |
 *
 * Severity is carried by ink weight and by hatching, not by colour. The fourth row is ADR-0085's
 * `null`, which is not a fourth `outcome` value — the enum stays three-valued — but it still has to
 * draw, and drawing nothing where the other three draw something is how "never looked" becomes
 * invisible.
 *
 * This applies to **every** surface that renders an outcome: the rail chip, the popover, the banner,
 * the node marks and the inspector caveat.
 */

const LABELS: Record<string, string> = {
  COMPLETE: 'Complete',
  PARTIAL: 'Partial',
  FAILED: 'Failed',
  UNREPORTED: 'Not reported',
}

export function outcomeLabel(outcome: OutcomeStatus | null): string {
  return LABELS[outcome ?? 'UNREPORTED'] ?? 'Not reported'
}

export function OutcomeGlyph({ outcome }: { outcome: OutcomeStatus | null }) {
  const label = outcomeLabel(outcome)
  return (
    <svg
      className={`outcome-glyph outcome-${(outcome ?? 'unreported').toLowerCase()}`}
      viewBox="0 0 16 16"
      width="14"
      height="14"
      role="img"
      aria-label={label}
    >
      <title>{label}</title>
      {outcome === 'COMPLETE' ? (
        <rect x="3" y="3" width="10" height="10" rx="2.2" />
      ) : (
        <>
          <rect x="3.6" y="3.6" width="8.8" height="8.8" rx="2" fill="none" strokeWidth="1.6" />
          {/* Hatched for PARTIAL, crossed for FAILED — the two severities are told apart by ink
              rather than by hue, so the distinction survives a monochrome screenshot. */}
          {outcome === 'PARTIAL' && (
            <>
              <line x1="4" y1="10" x2="10" y2="4" strokeWidth="1.2" />
              <line x1="6" y1="12" x2="12" y2="6" strokeWidth="1.2" />
            </>
          )}
          {outcome === 'FAILED' && (
            <>
              <line x1="5.4" y1="5.4" x2="10.6" y2="10.6" strokeWidth="1.7" />
              <line x1="10.6" y1="5.4" x2="5.4" y2="10.6" strokeWidth="1.7" />
            </>
          )}
        </>
      )}
    </svg>
  )
}
