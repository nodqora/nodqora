-- Nodqora, slice 3: the fast half gets its store (ADR-0072).
--
-- V1 built the slow half as store-plus-fold and left `node_state` with nothing writing it. This
-- makes the fast half symmetric: a header per (plugin, environment), one retained contribution per
-- (node, plugin), and `node_state` demoted to what it always was — a derived cache.
--
-- The symmetry is not tidiness. Several plugins observe one node and they poll independently, so
-- composing that node's row needs a contribution that arrived at a different moment; without a
-- store there is nowhere for it to have been. And ADR-0024 discards abstentions *before* it
-- collapses, so the composed row has already thrown away what a re-collapse would need: you cannot
-- recompute a collapse from its own output. In-place merge into `node_state` is structurally
-- impossible rather than merely discouraged.

-- ---------------------------------------------------------------- the contribution store

-- The structural twin of `plugin_snapshot`, and deliberately a second table rather than a
-- `capability` discriminator on the first: they are written by different loops on different
-- cadences and read by different endpoints, and share only a column list.
create table health_run (
    environment_key text        not null references environment (key) on delete cascade,
    plugin_id       text        not null references plugin (id) on delete cascade,
    outcome         text        not null check (outcome in ('COMPLETE', 'PARTIAL', 'FAILED')),
    reasons         jsonb       not null default '[]'::jsonb,
    recorded_at     timestamptz not null,
    payload_version integer     not null,
    primary key (environment_key, plugin_id)
);

-- ADR-0072: keyed by `node_id`, not by node key. A node that leaves and returns gets a new
-- surrogate id and therefore a fresh NodeState, reading UNKNOWN for up to one fast-loop interval —
-- which ADR-0050 calls correct rather than merely tolerable. Keying by node key would let a
-- returning node instantly re-inherit contributions from before it vanished, quietly overturning
-- that, and would do it invisibly.
--
-- The FK also *is* ADR-0050's "a write for a nodeId that no longer exists is dropped, not an
-- error": the writer inserts through `INSERT ... SELECT ... FROM node WHERE ...`, so a node deleted
-- by a concurrent discovery fold yields zero rows rather than a constraint violation.
create table health_contribution (
    node_id         bigint      not null references node (id) on delete cascade,
    plugin_id       text        not null references plugin (id) on delete cascade,
    health          text        not null
        check (health in ('HEALTHY', 'DEGRADED', 'UNHEALTHY', 'DISABLED')),
    raw_signal      text,
    metrics         jsonb       not null default '{}'::jsonb,
    observed_at     timestamptz not null,
    payload_version integer     not null,
    primary key (node_id, plugin_id)
);

-- UNKNOWN is absent from that CHECK on purpose (ADR-0104). An abstention is an omission: a plugin
-- that did not look contributes nothing, so there is no row, and ADR-0028's outer join synthesizes
-- UNKNOWN / null / {} / null. Storing an abstention would give the fast half two ways to be
-- UNKNOWN that disagree about `observedAt` — one where the composed row does not exist, one where
-- it exists and reports a freshness for an observation nobody made.

create index health_contribution_by_plugin on health_contribution (plugin_id);
