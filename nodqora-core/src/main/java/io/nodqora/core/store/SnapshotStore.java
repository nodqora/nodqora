package io.nodqora.core.store;

import com.fasterxml.jackson.core.type.TypeReference;
import io.nodqora.core.fold.SnapshotView;
import io.nodqora.core.graph.Keys;
import io.nodqora.plugin.api.DiscoveredEdge;
import io.nodqora.plugin.api.DiscoveredNode;
import io.nodqora.plugin.api.DiscoveredOwner;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.OutcomeStatus;
import io.nodqora.plugin.api.TypeDescriptor;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The engine's retained copy of the latest accepted {@code DiscoveryResult} per
 * {@code (plugin, environment)} — durable, and the source of truth (ADR-0043).
 *
 * <p>{@code outcome} is its transition function (ADR-0046, ADR-0086):
 *
 * <table>
 *   <tr><th>outcome</th><th>header</th><th>entries</th></tr>
 *   <tr><td>COMPLETE</td><td>written</td><td>upsert present, delete absent</td></tr>
 *   <tr><td>PARTIAL</td><td>written</td><td>upsert present, retain absent</td></tr>
 *   <tr><td>FAILED</td><td>written</td><td>untouched</td></tr>
 * </table>
 *
 * <p>A {@code PARTIAL} upserts <em>whole</em> entries: field-level absence within a present key is
 * not honoured, because honouring it is an emptiness guard creeping back into a fold that was made
 * order-independent specifically to have none.
 */
@Component
public class SnapshotStore {

    private static final String NODE = "NODE";
    private static final String EDGE = "EDGE";
    private static final String OWNER = "OWNER";
    private static final String TYPE_DESCRIPTOR = "TYPE_DESCRIPTOR";

    private final JdbcTemplate jdbc;
    private final Json json;

    public SnapshotStore(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Header and entries commit together, so a snapshot lands whole or not at all (ADR-0043). */
    @Transactional
    public void record(String environmentKey, String pluginId, DiscoveryResult result, Instant recordedAt) {
        writeHeader(environmentKey, pluginId, result, recordedAt);
        if (result.outcome().status() == OutcomeStatus.FAILED) {
            return;
        }

        List<String> present = new ArrayList<>();
        result.nodes().forEach(node ->
                present.add(upsertKeyed(environmentKey, pluginId, NODE, node.key(), node, recordedAt)));
        result.owners().forEach(owner ->
                present.add(upsertKeyed(environmentKey, pluginId, OWNER, owner.key(), owner, recordedAt)));
        result.descriptors().forEach(descriptor -> present.add(
                upsertKeyed(environmentKey, pluginId, TYPE_DESCRIPTOR, descriptor.type(), descriptor, recordedAt)));

        List<String> presentEdges = new ArrayList<>();
        result.edges().forEach(edge -> presentEdges.add(upsertEdge(environmentKey, pluginId, edge, recordedAt)));

        if (result.outcome().status() == OutcomeStatus.COMPLETE) {
            // ADR-0047: absence from a COMPLETE snapshot deletes immediately. No N-consecutive rule
            // and no delta threshold: deletion here is non-destructive and self-healing, while
            // over-retention is permanent and corrupts drift-is-absence.
            deleteAbsent(environmentKey, pluginId, present, presentEdges);
        }
    }

    private void writeHeader(String environmentKey, String pluginId, DiscoveryResult result, Instant recordedAt) {
        jdbc.update(
                """
                insert into plugin_snapshot
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
                java.sql.Timestamp.from(recordedAt),
                PayloadVersion.CURRENT);
    }

    private String upsertKeyed(
            String environmentKey, String pluginId, String kind, String key, Object payload, Instant confirmedAt) {
        jdbc.update(
                """
                insert into plugin_snapshot_entry
                    (environment_key, plugin_id, entity_kind, entity_key, payload, confirmed_at, payload_version)
                values (?, ?, ?, ?, ?::jsonb, ?, ?)
                on conflict (environment_key, plugin_id, entity_kind, lower(btrim(entity_key)))
                    where entity_kind <> 'EDGE'
                do update set entity_key = excluded.entity_key,
                              payload = excluded.payload,
                              confirmed_at = excluded.confirmed_at,
                              payload_version = excluded.payload_version
                """,
                environmentKey,
                pluginId,
                kind,
                key,
                json.write(payload),
                java.sql.Timestamp.from(confirmedAt),
                PayloadVersion.CURRENT);
        return kind + " " + Keys.folded(key);
    }

    private String upsertEdge(String environmentKey, String pluginId, DiscoveredEdge edge, Instant confirmedAt) {
        jdbc.update(
                """
                insert into plugin_snapshot_entry
                    (environment_key, plugin_id, entity_kind, from_key, to_key, relation,
                     payload, confirmed_at, payload_version)
                values (?, ?, 'EDGE', ?, ?, ?, ?::jsonb, ?, ?)
                on conflict (environment_key, plugin_id, lower(btrim(from_key)), lower(btrim(to_key)), relation)
                    where entity_kind = 'EDGE'
                do update set from_key = excluded.from_key,
                              to_key = excluded.to_key,
                              payload = excluded.payload,
                              confirmed_at = excluded.confirmed_at,
                              payload_version = excluded.payload_version
                """,
                environmentKey,
                pluginId,
                edge.fromKey(),
                edge.toKey(),
                edge.relation(),
                json.write(edge),
                java.sql.Timestamp.from(confirmedAt),
                PayloadVersion.CURRENT);
        return Keys.folded(edge.fromKey()) + " " + Keys.folded(edge.toKey()) + " " + edge.relation();
    }

    private void deleteAbsent(
            String environmentKey, String pluginId, List<String> presentKeyed, List<String> presentEdges) {
        jdbc.query(
                """
                select id, entity_kind, entity_key, from_key, to_key, relation
                from plugin_snapshot_entry
                where environment_key = ? and plugin_id = ?
                """,
                rs -> {
                    List<Long> doomed = new ArrayList<>();
                    while (rs.next()) {
                        String kind = rs.getString("entity_kind");
                        boolean present = EDGE.equals(kind)
                                ? presentEdges.contains(Keys.folded(rs.getString("from_key")) + " "
                                        + Keys.folded(rs.getString("to_key")) + " " + rs.getString("relation"))
                                : presentKeyed.contains(kind + " " + Keys.folded(rs.getString("entity_key")));
                        if (!present) {
                            doomed.add(rs.getLong("id"));
                        }
                    }
                    if (!doomed.isEmpty()) {
                        jdbc.batchUpdate(
                                "delete from plugin_snapshot_entry where id = ?",
                                doomed.stream().map(id -> new Object[] {id}).toList());
                    }
                    return null;
                },
                environmentKey,
                pluginId);
    }

    // ---------------------------------------------------------------- reading

    /** What the fold reads: every plugin's stored opinion about one environment. */
    public List<SnapshotView> read(String environmentKey) {
        Map<String, List<DiscoveredNode>> nodes = new LinkedHashMap<>();
        Map<String, List<DiscoveredEdge>> edges = new LinkedHashMap<>();
        Map<String, List<DiscoveredOwner>> owners = new LinkedHashMap<>();
        List<String> plugins = new ArrayList<>();

        jdbc.query(
                """
                select plugin_id, entity_kind, payload
                from plugin_snapshot_entry
                where environment_key = ? and entity_kind <> 'TYPE_DESCRIPTOR'
                order by plugin_id, entity_kind, id
                """,
                rs -> {
                    String pluginId = rs.getString("plugin_id");
                    if (!plugins.contains(pluginId)) {
                        plugins.add(pluginId);
                    }
                    String payload = rs.getString("payload");
                    switch (rs.getString("entity_kind")) {
                        case NODE -> nodes.computeIfAbsent(pluginId, ignored -> new ArrayList<>())
                                .add(json.read(payload, DiscoveredNode.class));
                        case EDGE -> edges.computeIfAbsent(pluginId, ignored -> new ArrayList<>())
                                .add(json.read(payload, DiscoveredEdge.class));
                        case OWNER -> owners.computeIfAbsent(pluginId, ignored -> new ArrayList<>())
                                .add(json.read(payload, DiscoveredOwner.class));
                        default -> throw new IllegalStateException("unreachable entity kind");
                    }
                },
                environmentKey);

        return plugins.stream()
                .map(pluginId -> new SnapshotView(
                        pluginId,
                        nodes.getOrDefault(pluginId, List.of()),
                        edges.getOrDefault(pluginId, List.of()),
                        owners.getOrDefault(pluginId, List.of())))
                .toList();
    }

    public List<SnapshotHeader> headers(String environmentKey) {
        return jdbc.query(
                """
                select environment_key, plugin_id, outcome, reasons, recorded_at
                from plugin_snapshot where environment_key = ? order by plugin_id
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
     * ADR-0056 and ADR-0079: when each plugin's snapshot last actually carried each node key —
     * the maximum over the plugin's node entry and over any edge naming the key as an endpoint.
     * Keyed by the folded node key.
     */
    public Map<String, Map<String, Instant>> confirmedAt(String environmentKey) {
        Map<String, Map<String, Instant>> byKey = new LinkedHashMap<>();
        jdbc.query(
                """
                select folded_key, plugin_id, max(confirmed_at) as confirmed_at from (
                    select lower(btrim(entity_key)) as folded_key, plugin_id, confirmed_at
                        from plugin_snapshot_entry where environment_key = ? and entity_kind = 'NODE'
                    union all
                    select lower(btrim(from_key)), plugin_id, confirmed_at
                        from plugin_snapshot_entry where environment_key = ? and entity_kind = 'EDGE'
                    union all
                    select lower(btrim(to_key)), plugin_id, confirmed_at
                        from plugin_snapshot_entry where environment_key = ? and entity_kind = 'EDGE'
                ) named group by folded_key, plugin_id
                """,
                (ResultSet rs) -> {
                    byKey.computeIfAbsent(rs.getString("folded_key"), ignored -> new LinkedHashMap<>())
                            .put(rs.getString("plugin_id"), rs.getTimestamp("confirmed_at").toInstant());
                },
                environmentKey,
                environmentKey,
                environmentKey);
        return byKey;
    }

    /**
     * ADR-0077: there is no descriptor table. The global set is a read-time projection over every
     * environment's descriptor entries, with contested type ids resolved by plugin precedence first
     * and then by the config's environment order. Both orders already exist and are deterministic,
     * so no new ordering rule is invented, and a descriptor lives exactly as long as some plugin's
     * current snapshot claims it.
     */
    public List<TypeDescriptor> typeDescriptors(
            Comparator<String> byPluginPrecedence, Comparator<String> byEnvironmentOrder) {
        record Candidate(String environmentKey, String pluginId, TypeDescriptor descriptor) {}

        List<Candidate> candidates = jdbc.query(
                """
                select environment_key, plugin_id, payload
                from plugin_snapshot_entry where entity_kind = 'TYPE_DESCRIPTOR'
                """,
                (rs, row) -> new Candidate(
                        rs.getString("environment_key"),
                        rs.getString("plugin_id"),
                        json.read(rs.getString("payload"), TypeDescriptor.class)));

        Map<String, TypeDescriptor> winners = new LinkedHashMap<>();
        candidates.stream()
                .sorted(Comparator.comparing(Candidate::pluginId, byPluginPrecedence)
                        .thenComparing(Candidate::environmentKey, byEnvironmentOrder))
                .forEach(candidate -> winners.putIfAbsent(candidate.descriptor().type(), candidate.descriptor()));

        return winners.values().stream()
                .sorted(Comparator.comparing(TypeDescriptor::type))
                .toList();
    }

    /**
     * ADR-0080: any row whose payload version differs from the current constant is discarded at
     * startup. The plugins repopulate within one cadence.
     */
    @Transactional
    public int discardStalePayloads() {
        int entries = jdbc.update(
                "delete from plugin_snapshot_entry where payload_version <> ?", PayloadVersion.CURRENT);
        int headers = jdbc.update("delete from plugin_snapshot where payload_version <> ?", PayloadVersion.CURRENT);
        return entries + headers;
    }
}
