/**
 * The node card's one optional metric line, composed from ADR-0006's plugin-namespaced map.
 *
 * The core never looks inside `metrics` and neither does this: it renders whatever keys a plugin
 * allow-listed (ADR-0028), in a stable order, with no per-plugin and no per-type branch. A Kafka
 * topic and a Kubernetes workload differ only in which keys arrive.
 *
 * Ordering is alphabetical by plugin and then by key, rather than the registry order `rawSignal`
 * uses. `rawSignal` is a sentence and reads in a fixed sequence by design; this is a label, the
 * frontend does not receive the registry order, and asking the server for one to render a metric
 * overlay would put a presentation concern into ADR-0055's roster.
 */
export function summarizeMetrics(metrics: Record<string, Record<string, unknown>>): string | null {
  const parts = Object.entries(metrics)
    .sort(([a], [b]) => a.localeCompare(b))
    .flatMap(([, values]) =>
      Object.entries(values)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([name, value]) => `${humanize(name)} ${value}`),
    )
  return parts.length === 0 ? null : parts.join(' · ')
}

/**
 * `desiredReplicas` reads as "desired replicas". The allow-list is camelCase because it is a wire
 * contract; a human-readable label is presentation, so it is derived here rather than shipped —
 * which also means a new allow-listed key needs no frontend change to render.
 */
function humanize(name: string) {
  return name
    .replace(/([A-Z])/g, ' $1')
    .toLowerCase()
    .trim()
}
