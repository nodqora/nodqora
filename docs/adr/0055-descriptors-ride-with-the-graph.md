# ADR-0055: Descriptors ride with the graph; the rosters are static meta

- **Status**: Accepted
- **Date**: 2026-09-02
- **Ticket**: [REST API shape](https://github.com/fredskor/nodqora/issues/13)

## Context

Four global things are API surface: the **environment roster**, the **plugin
roster** (`yaml` · `kubernetes` · `kafka` · `connect` — the `metadata` keys, the
`sources[]` entries, the `backings[].plugin` values), **TypeDescriptors** and
**RelationDescriptors**.

They do not share a lifetime. ADR-0014 makes plugin and environment config
file-declared and bound at startup, so both rosters are static until a restart.
Descriptors are not: ADR-0012 has them ride in the DiscoveryResult, registered
by plugins and by the YAML topology alike, so they appear as discovery runs.

## Decision

Split by lifetime.

```text
GET /api/meta      -> { environments[], plugins[], refresh{} }   (static)
GET /.../graph     -> { ..., typeDescriptors[], relationDescriptors[] }
```

`/api/meta` is fetched once. The environment roster is `{key, displayName}` and
nothing else (ADR-0014, ADR-0035) — no connection config, no secrets, no
plugin-shaped fields.

Descriptors are served **inside the graph document**, on the discovery cadence.

## Consequences

- ADR-0043 commits a Node and the descriptor for its `type` in the **same atomic
  fold**. Serving them from two endpoints reopens the race the fold closed: a
  canvas holding a node whose type it cannot draw. Coupling them means the
  vocabulary needed to render the graph always arrives with the graph.
- The alternative — one bootstrap document fetched at startup — is safe by
  ADR-0001's fallback descriptor, but it renders a freshly-declared
  `iceberg-table` with a generic icon for no reason the user can see, until the
  page is reloaded.
- Cost: roughly fifteen descriptor entries are re-sent on every environment
  poll. Descriptors are global while the graph document is environment-scoped,
  so what is served is a superset — harmless, and stated here so it is not later
  mistaken for environment-scoped descriptors.
- The frontend resolves `sources[]`, `backings[].plugin` and `metadata` keys to
  labels through the plugin roster, and phrases Connections through the
  RelationDescriptor's orientation (ADR-0002, ADR-0019) — so the API never
  serves pre-phrased edge text.
