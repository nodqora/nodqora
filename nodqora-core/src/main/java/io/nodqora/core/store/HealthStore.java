package io.nodqora.core.store;

import com.fasterxml.jackson.core.type.TypeReference;
import io.nodqora.core.fold.ContributionView;
import io.nodqora.core.graph.Keys;
import io.nodqora.plugin.api.Health;
import io.nodqora.plugin.api.HealthCapability.HealthResult;
import io.nodqora.plugin.api.OutcomeStatus;
import io.nodqora.plugin.api.StateContribution;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The engine's retained copy of the latest accepted {@code HealthResult} per
 * {@code (plugin, environment)} — durable, and the source of truth for the fast half (ADR-0072).
 *
 * <p>The structural twin of {@link SnapshotStore}, down to the transition function, because
 * ADR-0026 gave {@code HealthResult} the same three outcomes as {@code DiscoveryResult} for the
 * same reason:
 *
 * <table>
 *   <tr><th>outcome</th><th>header</th><th>contributions</th></tr>
 *   <tr><td>COMPLETE</td><td>written</td><td>upsert present, delete absent</td></tr>
 *   <tr><td>PARTIAL</td><td>written</td><td>upsert present, retain absent</td></tr>
 *   <tr><td>FAILED</td><td>written</td><td>untouched</td></tr>
 * </table>
 *
 * <p>The header is written on every poll whatever the outcome, which is what keeps "nobody has ever
 * polled this pair" distinguishable from "we tried and could not look" (ADR-0086).
 *
 * <p><b>An abstention is an omission (ADR-0104).</b> An {@code UNKNOWN} contribution is dropped here
 * rather than stored, so it is deleted under {@code COMPLETE} exactly as a genuinely absent key
 * would be. That is enforced at this one point rather than asked of four plugins: a stored
 * abstention would give the fast half two ways to be {@code UNKNOWN} — one where the composed row
 * does not exist, one where it exists carrying a freshness for an observation nobody made — and
 * ADR-0024 discards it a step later regardless.
 */
@Component
public class HealthStore {

    private static final Logger log = LoggerFactory.getLogger(HealthStore.class);

    private static final TypeReference<Map<String, Object>> METRICS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final Json json;

    public HealthStore(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Header and contributions commit together, so a run lands whole or not at all. */
    @Transactional
    public void record(String environmentKey, String pluginId, HealthResult result, Instant observedAt) {
        writeHeader(environmentKey, pluginId, result, observedAt);
        if (result.outcome().status() == OutcomeStatus.FAILED) {
            // ADR-0046: a FAILED run changes nothing. The last good contributions stand and go stale
            // visibly through `observedAt`, rather than the node flipping to UNKNOWN because the
            // plugin blinked — which ADR-0026 says is a lie about the node rather than about us.
            return;
        }

        List<String> present = new ArrayList<>();
        for (Map.Entry<String, StateContribution> observed : result.contributions().entrySet()) {
            StateContribution contribution = observed.getValue();
            if (contribution.health() == Health.UNKNOWN) {
                continue;
            }
            upsert(environmentKey, pluginId, observed.getKey(), contribution, observedAt);
            present.add(Keys.folded(observed.getKey()));
        }

        if (result.outcome().status() == OutcomeStatus.COMPLETE) {
            deleteAbsent(environmentKey, pluginId, present);
        }
    }

    private void writeHeader(String environmentKey, String pluginId, HealthResult result, Instant recordedAt) {
        jdbc.update(
                """
                insert into health_run
                    (environment_key, plugin_id, outcome, reasons, recorded_at, payload_version)
                values (?, ?, ?, ?::jsonb, ?, ?)
                on conflict (environment_key, plugin_id) do update
                set outcome = excluded.outcome,
                    reasons = excluded.reasons,
                    recorded_at = excluded.recorded_at,
                    payload_version = excluded.payload_version
                """,
                environmentKey,
                pluginId,
                result.outcome().status().name(),
                json.write(result.outcome().reasons()),
                Timestamp.from(recordedAt),
                PayloadVersion.CURRENT);
    }

    /**
     * ADR-0075: written as {@code INSERT … SELECT … FROM node WHERE …} rather than a bare insert, so
     * a node deleted by a concurrent discovery fold yields <em>zero rows</em> instead of a foreign
     * key violation. ADR-0050's "a NodeState write for a nodeId that no longer exists is dropped,
     * not an error" is this {@code WHERE} clause and not a caught exception.
     *
     * <p>The lookup is case-folded, because the node key a plugin returns is the key it resolved
     * (ADR-0020) and nothing guarantees it is spelled the way the fold stored it.
     */
    private void upsert(
            String environmentKey,
            String pluginId,
            String nodeKey,
            StateContribution contribution,
            Instant observedAt) {
        int written = jdbc.update(
                """
                insert into health_contribution
                    (node_id, plugin_id, health, raw_signal, metrics, observed_at, payload_version)
                select n.id, ?, ?, ?, ?::jsonb, ?, ?
                from node n
                where n.environment_key = ? and lower(btrim(n.key)) = ?
                on conflict (node_id, plugin_id) do update
                set health = excluded.health,
                    raw_signal = excluded.raw_signal,
                    metrics = excluded.metrics,
                    observed_at = excluded.observed_at,
                    payload_version = excluded.payload_version
                """,
                pluginId,
                contribution.health().name(),
                contribution.rawSignal(),
                json.write(contribution.metrics()),
                Timestamp.from(observedAt),
                PayloadVersion.CURRENT,
                environmentKey,
                Keys.folded(nodeKey));

        if (written == 0) {
            log.debug(
                    "health {}/{} reported node '{}', which the graph does not carry; dropped",
                    environmentKey,
                    pluginId,
                    nodeKey);
        }
    }

    /**
     * ADR-0047, on the fast half: absence from a {@code COMPLETE} run deletes immediately. The
     * plugin was asked about the node and did not answer for it, which is the same event as the node
     * leaving its scope — and deletion here is non-destructive and self-healing within thirty
     * seconds, while retention would leave a stale verdict standing with no way to tell.
     */
    private void deleteAbsent(String environmentKey, String pluginId, List<String> present) {
        record Carried(long nodeId, String foldedKey) {}

        List<Object[]> doomed = jdbc
                .query(
                        """
                        select c.node_id, lower(btrim(n.key)) as folded_key
                        from health_contribution c
                        join node n on n.id = c.node_id
                        where n.environment_key = ? and c.plugin_id = ?
                        """,
                        (rs, row) -> new Carried(rs.getLong("node_id"), rs.getString("folded_key")),
                        environmentKey,
                        pluginId)
                .stream()
                .filter(carried -> !present.contains(carried.foldedKey()))
                .map(carried -> new Object[] {carried.nodeId(), pluginId})
                .toList();

        if (!doomed.isEmpty()) {
            jdbc.batchUpdate("delete from health_contribution where node_id = ? and plugin_id = ?", doomed);
        }
    }

    // ---------------------------------------------------------------- reading

    /** What the state fold reads: every plugin's retained observation of one environment. */
    public List<ContributionView> read(String environmentKey) {
        return jdbc.query(
                """
                select c.node_id, c.plugin_id, c.health, c.raw_signal, c.metrics, c.observed_at
                from health_contribution c
                join node n on n.id = c.node_id
                where n.environment_key = ?
                order by c.node_id, c.plugin_id
                """,
                (rs, row) -> new ContributionView(
                        rs.getLong("node_id"),
                        rs.getString("plugin_id"),
                        Health.valueOf(rs.getString("health")),
                        rs.getString("raw_signal"),
                        json.read(rs.getString("metrics"), METRICS),
                        rs.getTimestamp("observed_at").toInstant()),
                environmentKey);
    }

    /** ADR-0056: the row {@code /state}'s outcome block reads — ADR-0071's header, on the fast half. */
    public List<SnapshotHeader> headers(String environmentKey) {
        return jdbc.query(
                """
                select environment_key, plugin_id, outcome, reasons, recorded_at
                from health_run where environment_key = ? order by plugin_id
                """,
                (rs, row) -> new SnapshotHeader(
                        rs.getString("environment_key"),
                        rs.getString("plugin_id"),
                        OutcomeStatus.valueOf(rs.getString("outcome")),
                        json.read(rs.getString("reasons"), new TypeReference<List<String>>() {}),
                        rs.getTimestamp("recorded_at").toInstant()),
                environmentKey);
    }

    /**
     * ADR-0080, and the fast half self-heals in thirty seconds rather than five minutes: a stored
     * contribution written under a different payload version is discarded rather than migrated.
     */
    @Transactional
    public int discardStalePayloads() {
        int contributions =
                jdbc.update("delete from health_contribution where payload_version <> ?", PayloadVersion.CURRENT);
        int runs = jdbc.update("delete from health_run where payload_version <> ?", PayloadVersion.CURRENT);
        return contributions + runs;
    }
}
