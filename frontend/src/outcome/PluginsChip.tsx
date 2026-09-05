import { useEffect, useRef, useState } from 'react'
import { OutcomeGlyph, outcomeLabel } from './OutcomeGlyph'
import { chipText, popoverRows, type Rosters } from './plugins'

/**
 * ADR-0081: **a resting chip in the rail, beside the environment switcher, at all times.**
 *
 * It opens a popover carrying every plugin's two capabilities, its `outcome`, its reasons or cause,
 * and when each was recorded.
 *
 * The chip earns its place separately from the banner: without it, *"is everything actually being
 * watched?"* is unanswerable on a good day, and the trouble encodings have nowhere to have come from
 * the first time they fire. It is the one thing on this surface with a standing cost, and the cost
 * is one rail element and zero canvas pixels.
 *
 * A **permanent second channel** — a coverage row on every node card — was rejected on two counts.
 * It is redundant with a decision already made: ADR-0017's dashed ring already answers *"is anything
 * watching this?"*, so on four of the fixture's ten nodes the coverage row would print "not
 * observed" directly beneath a glyph that means not observed. And a channel that reads
 * `COMPLETE COMPLETE COMPLETE` for weeks is one people stop reading, which is precisely the wrong
 * property for the day it changes.
 */
export function PluginsChip({ rosters, label }: { rosters: Rosters; label: (pluginId: string) => string }) {
  const [open, setOpen] = useState(false)
  const container = useRef<HTMLDivElement>(null)

  // A click anywhere else closes it. The popover is a disclosure, not a mode.
  useEffect(() => {
    if (!open) return
    const dismiss = (event: MouseEvent) => {
      if (!container.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', dismiss)
    return () => document.removeEventListener('mousedown', dismiss)
  }, [open])

  const rows = popoverRows(rosters)

  return (
    <div className="plugins-chip" ref={container}>
      <button
        type="button"
        className="plugins-chip-button"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        {chipText(rosters)}
      </button>

      {open && (
        <div className="plugins-popover" role="dialog" aria-label="Plugin outcomes">
          {rows.length === 0 ? (
            <p className="empty">No plugins are configured for this environment.</p>
          ) : (
            <ul>
              {rows.map((row) => (
                <li key={`${row.plugin} ${row.capability}`}>
                  <OutcomeGlyph outcome={row.outcome} />
                  <span className="plugins-popover-name">
                    {label(row.plugin)}
                    <span className="rel">{row.capability.toLowerCase()}</span>
                  </span>
                  <span className="plugins-popover-outcome">
                    {outcomeLabel(row.outcome)}
                    {/* ADR-0085: an unreported pair has no `recordedAt` to render, and inventing
                        one would be the fabrication ADR-0058 forbids. */}
                    <span className="rel">
                      {row.recordedAt ? new Date(row.recordedAt).toLocaleString() : 'never'}
                    </span>
                  </span>
                  {row.detail && <p className="plugins-popover-detail">{row.detail}</p>}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
