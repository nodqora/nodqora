/**
 * ADR-0010: `TypeDescriptor.icon` is a **name** from a fixed frontend icon set, never a shipped
 * asset — so a plugin can register a type without shipping anything, and an unknown name falls back
 * exactly as an unknown type does (ADR-0001).
 *
 * A node carries two marks and they are not the same mark: this one says what it *is*, and
 * ADR-0017's glyph says how it is *doing*.
 */

const ICONS: Record<string, JSX.Element> = {
  cloud: <path d="M5 13a3.2 3.2 0 0 1 .5-6.4 4.2 4.2 0 0 1 8 .9A2.8 2.8 0 0 1 13 13Z" />,
  search: (
    <>
      <circle cx="7.2" cy="7.2" r="4.2" fill="none" strokeWidth="1.6" />
      <path d="M10.4 10.4 14 14" strokeWidth="1.6" strokeLinecap="round" />
    </>
  ),
  table: (
    <>
      <rect x="2.5" y="3" width="11" height="10" rx="1.2" fill="none" strokeWidth="1.4" />
      <path d="M2.5 6.6h11M6.4 6.6V13" strokeWidth="1.4" />
    </>
  ),
  terminal: (
    <>
      <rect x="2.5" y="3" width="11" height="10" rx="1.2" fill="none" strokeWidth="1.4" />
      <path d="M5 6.6 7.4 8.6 5 10.6M8.8 10.8h2.8" strokeWidth="1.4" strokeLinecap="round" fill="none" />
    </>
  ),
  service: (
    <>
      <rect x="2.5" y="3" width="11" height="4" rx="1.1" fill="none" strokeWidth="1.4" />
      <rect x="2.5" y="9" width="11" height="4" rx="1.1" fill="none" strokeWidth="1.4" />
    </>
  ),
  stream: (
    <>
      <path d="M2.6 5h10.8M2.6 8h10.8M2.6 11h10.8" strokeWidth="1.4" strokeLinecap="round" />
    </>
  ),
  /** ADR-0001's fallback: an unregistered type still renders, and unknown is a display state. */
  fallback: <rect x="3" y="3" width="10" height="10" rx="2" fill="none" strokeWidth="1.4" />,
}

export function TypeIcon({ icon }: { icon: string | null }) {
  const glyph = (icon && ICONS[icon]) ?? ICONS.fallback
  return (
    <svg className="type-icon" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
      {glyph}
    </svg>
  )
}
