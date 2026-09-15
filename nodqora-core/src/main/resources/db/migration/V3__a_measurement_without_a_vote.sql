-- Nodqora: a contribution may carry metrics without a vote (ADR-0165, amending ADR-0104).
--
-- V2 kept UNKNOWN out of `health_contribution.health` because an abstention was an omission. Only an
-- *empty* abstention still is. A contribution reading UNKNOWN whose `metrics` are non-empty is a
-- measurement by a plugin that never votes — Prometheus's `rate` and `latency` — and it has to be
-- stored, or the fold has nothing to put beside the replica count.
--
-- The rule that an empty abstention is not stored stays where it is enforced, in `HealthStore`,
-- rather than moving into this CHECK: "metrics non-empty" is a fact about a jsonb value, and the
-- store already drops the row before it is written.

alter table health_contribution drop constraint health_contribution_health_check;

alter table health_contribution
    add constraint health_contribution_health_check
        check (health in ('HEALTHY', 'DEGRADED', 'UNHEALTHY', 'DISABLED', 'UNKNOWN'));
