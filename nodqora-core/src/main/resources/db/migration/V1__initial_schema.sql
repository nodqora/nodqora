-- Nodqora, slice 1.
--
-- Two halves with opposite migration properties (ADR-0080). The *store* is the source of truth and
-- is the one thing that cannot be rebuilt; the *derived cache* below it can be dropped and
-- recreated at will, because ADR-0043's boot fold already supplies the repopulation for free.
--
-- One Flyway history over all of it: the store is not special enough to live outside migrations, it
-- still needs columns, indexes and constraints managed.

-- ---------------------------------------------------------------- config mirrors (ADR-0074)
--
-- Not a second source of truth. Nothing writes these but startup reconciliation, and they hold no
-- connection config, no secrets and no cadences. They exist so that ADR-0043's two cleanup rules
-- stop being a checklist and become an invariant: deleting the `staging` row takes its snapshots,
-- entries, nodes, edges, owners and states with it, including from the table someone adds next year
-- without reading ADR-0043.

create table environment (
    key          text primary key,
    display_name text not null
);

create table plugin (
    id text primary key
);

-- ---------------------------------------------------------------- the snapshot store (ADR-0071)
--
-- A header per (plugin, environment) plus one entry row per key that snapshot carries. Never a
-- single blob: PARTIAL is per-key by definition (ADR-0046) and ADR-0056's confirmedAt is per key
-- per plugin, both of which a blob turns into a read-modify-write over a hand-built index.
--
-- This is NOT the graph. It holds four plugins' unreconciled opinions, and a SELECT over it returns
-- payments-enricher once per plugin that knows it.

create table plugin_snapshot (
    environment_key text        not null references environment (key) on delete cascade,
    plugin_id       text        not null references plugin (id) on delete cascade,
    outcome         text        not null check (outcome in ('COMPLETE', 'PARTIAL', 'FAILED')),
    reasons         jsonb       not null default '[]'::jsonb,
    recorded_at     timestamptz not null,
    payload_version integer     not null,
    primary key (environment_key, plugin_id)
);

create table plugin_snapshot_entry (
    id              bigserial primary key,
    environment_key text        not null references environment (key) on delete cascade,
    plugin_id       text        not null references plugin (id) on delete cascade,
    entity_kind     text        not null
        check (entity_kind in ('NODE', 'EDGE', 'OWNER', 'TYPE_DESCRIPTOR')),
    entity_key      text,
    from_key        text,
    to_key          text,
    relation        text,
    payload         jsonb       not null,
    -- ADR-0056: when that plugin's snapshot last actually carried this key. A column rather than a
    -- field inside the payload, so the read-time projection is a join and not a parse.
    confirmed_at    timestamptz not null,
    payload_version integer     not null
);

-- Identity is carried in columns, not in a composed string: ADR-0020 pins no character class on a
-- node key, so no delimiter provably cannot appear in a fromKey, and a collision would silently
-- upsert two distinct edges onto each other.
create unique index plugin_snapshot_entry_identity
    on plugin_snapshot_entry (environment_key, plugin_id, entity_kind, lower(btrim(entity_key)))
    where entity_kind <> 'EDGE';

-- An edge's identity is the full tuple, endpoints folded and the relation matched exactly
-- (ADR-0045, ADR-0071).
create unique index plugin_snapshot_entry_edge_identity
    on plugin_snapshot_entry (environment_key, plugin_id,
                              lower(btrim(from_key)), lower(btrim(to_key)), relation)
    where entity_kind = 'EDGE';

create index plugin_snapshot_entry_by_pair
    on plugin_snapshot_entry (environment_key, plugin_id, entity_kind);

-- TypeDescriptors are global, so their read-time projection reads across every environment
-- (ADR-0077).
create index plugin_snapshot_entry_descriptors
    on plugin_snapshot_entry (entity_kind, lower(btrim(entity_key)))
    where entity_kind = 'TYPE_DESCRIPTOR';

-- ---------------------------------------------------------------- the derived cache (ADR-0043)
--
-- A fold of the store, not the source of truth. Nothing writes these but the fold, and both halves
-- can be rebuilt from the store at any time. Droppable in a migration: the boot fold is the
-- repopulation step, with no marker and no manual trigger (ADR-0080).
--
-- Collections are JSONB columns rather than child tables (ADR-0073). ADR-0050 makes canonical
-- collection ordering a storage obligation with a named silent failure, and a JSONB array *is*
-- ordered while a child table's order is an `ordinal` column plus an ORDER BY somebody will omit.

create table node (
    id              bigserial primary key,
    environment_key text        not null references environment (key) on delete cascade,
    key             text        not null,
    type            text,
    display_name    text,
    description     text,
    owner_key       text,
    links           jsonb       not null default '[]'::jsonb,
    backings        jsonb       not null default '[]'::jsonb,
    metadata        jsonb       not null default '{}'::jsonb,
    sources         jsonb       not null default '[]'::jsonb,
    discovered_at   timestamptz not null,
    updated_at      timestamptz not null
);

-- ADR-0020: stored verbatim and trimmed, unique on the case-folded form. This is the only
-- mechanism by which two plugins' output becomes one node.
create unique index node_natural_key on node (environment_key, lower(btrim(key)));

create table edge (
    id              bigserial primary key,
    environment_key text        not null references environment (key) on delete cascade,
    from_key        text        not null,
    to_key          text        not null,
    relation        text        not null,
    metadata        jsonb       not null default '{}'::jsonb,
    sources         jsonb       not null default '[]'::jsonb,
    discovered_at   timestamptz not null,
    updated_at      timestamptz not null
);

-- ADR-0078: endpoint keys, not node ids, and no foreign key to `node`. Ids would make ADR-0050's
-- edge diff sensitive to surrogate-id churn, reporting a topology change on an edge whose own
-- definition says nothing about it changed. That the endpoints resolve is the fold's job.
create unique index edge_identity
    on edge (environment_key, lower(btrim(from_key)), lower(btrim(to_key)), relation);

create table owner (
    id              bigserial primary key,
    environment_key text        not null references environment (key) on delete cascade,
    key             text        not null,
    display_name    text,
    channel         text,
    on_call         text,
    discovered_at   timestamptz not null,
    updated_at      timestamptz not null
);

-- ADR-0071: an owner key is case-folded exactly like a node key, and for the same reason: the
-- fixture's two team files are where payments-platform / Payments-Platform would otherwise produce
-- two owners and no error.
create unique index owner_natural_key on owner (environment_key, lower(btrim(key)));

-- The fast half's derived row (ADR-0003, ADR-0072). Slice 1 never writes it: `yaml` declares no
-- Health capability, so every node joins to nothing and reads UNKNOWN, which is the honest answer
-- and is the same answer /state will synthesize once contributions exist.
create table node_state (
    node_id     bigint primary key references node (id) on delete cascade,
    health      text        not null,
    raw_signal  text,
    metrics     jsonb       not null default '{}'::jsonb,
    observed_at timestamptz not null
);
