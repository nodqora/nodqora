import { OutcomeGlyph } from './OutcomeGlyph'
import { bannerSentence, type Banner } from './plugins'

/**
 * ADR-0081's exception surface: **nothing renders while every outcome is `COMPLETE`.** A
 * non-`COMPLETE` poll raises a banner above the canvas naming the plugin, the capability, the cause
 * and the affected node count — and marks the affected nodes (ADR-0083).
 *
 * The banner and the chip carry the only non-neutral treatment on this axis — a border and a tinted
 * ground — and neither is drawn from the health palette (ADR-0082).
 */
export function Banners({ banners, label }: { banners: Banner[]; label: (pluginId: string) => string }) {
  if (banners.length === 0) return null
  return (
    <div className="banners">
      {banners.map((banner) => (
        <p
          key={`${banner.plugin} ${banner.capability}`}
          className={`banner banner-${(banner.outcome ?? 'unreported').toLowerCase()}`}
          role="status"
        >
          <OutcomeGlyph outcome={banner.outcome} />
          {bannerSentence(banner, label)}
        </p>
      ))}
    </div>
  )
}
