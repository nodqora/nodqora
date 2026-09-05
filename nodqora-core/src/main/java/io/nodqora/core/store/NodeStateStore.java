package io.nodqora.core.store;

import com.fasterxml.jackson.core.type.TypeReference;
import io.nodqora.core.graph.FoldedNodeState;
import io.nodqora.plugin.api.Health;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The fast half's derived cache (ADR-0003, ADR-0028) — written only by the state fold, read by
 * {@code /state}.
 *
 * <p>A node nobody observes has no {@code node_state} row at all, and the outer join synthesizes
 * {@code UNKNOWN} / {@code rawSignal: null} / {@code metrics: {}} / {@code observedAt: null}. That is
 * arithmetic rather than a rule anyone wrote, and it is why this query needs no branch for the six
 * production fixture nodes that are permanently {@code UNKNOWN} — nor for a node every observer
 * abstained on, which reaches the same absence by the same route (ADR-0104).
 *
 * <p>Unlike {@link GraphStore} there is no ADR-0050 diff here, and its absence is the point:
 * {@code updatedAt} means <em>the topology changed</em> and lives on the slow side. This table is
 * expected to be rewritten every thirty seconds, so an upsert is honest where on the slow side it
 * would be the silent failure ADR-0050 exists to prevent.
 */
@Component
public class NodeStateStore {

    private static final TypeReference<Map<String, Object>> METRICS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final Json json;

    public NodeStateStore(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record NodeStateRow(
            String nodeKey, Health health, String rawSignal, Map<String, Object> metrics, Instant observedAt) {}

    /**
     * Replaces the environment's rows with what the fold computed: upsert what it produced, delete
     * what it did not.
     *
     * <p>The insert is {@code INSERT … SELECT … FROM node WHERE …} for ADR-0075's reason — a node
     * the discovery fold deleted between the contribution read and this write yields zero rows
     * rather than a foreign key violation. Deletion is scoped to <em>this environment's</em> nodes,
     * so a fold never reaches across ADR-0004's boundary even by accident.
     */
    public void write(String environmentKey, List<FoldedNodeState> states) {
        for (FoldedNodeState state : states) {
            jdbc.update(
                    """
                    insert into node_state (node_id, health, raw_signal, metrics, observed_at)
                    select n.id, ?, ?, ?::jsonb, ?
                    from node n where n.id = ? and n.environment_key = ?
                    on conflict (node_id) do update
                    set health = excluded.health,
                        raw_signal = excluded.raw_signal,
                        metrics = excluded.metrics,
                        observed_at = excluded.observed_at
                    """,
                    state.health().name(),
                    state.rawSignal(),
                    json.write(state.metrics()),
                    java.sql.Timestamp.from(state.observedAt()),
                    state.nodeId(),
                    environmentKey);
        }

        Set<Long> folded = states.stream().map(FoldedNodeState::nodeId).collect(Collectors.toSet());
        List<Object[]> doomed = jdbc
                .queryForList(
                        """
                        select s.node_id from node_state s
                        join node n on n.id = s.node_id
                        where n.environment_key = ?
                        """,
                        Long.class,
                        environmentKey)
                .stream()
                .filter(nodeId -> !folded.contains(nodeId))
                .map(nodeId -> new Object[] {nodeId})
                .toList();
        if (!doomed.isEmpty()) {
            jdbc.batchUpdate("delete from node_state where node_id = ?", doomed);
        }
    }

    public List<NodeStateRow> read(String environmentKey) {
        return jdbc.query(
                """
                select n.key,
                       coalesce(s.health, 'UNKNOWN') as health,
                       s.raw_signal,
                       coalesce(s.metrics, '{}'::jsonb) as metrics,
                       s.observed_at
                from node n
                left join node_state s on s.node_id = n.id
                where n.environment_key = ?
                order by lower(btrim(n.key))
                """,
                (rs, row) -> new NodeStateRow(
                        rs.getString("key"),
                        Health.valueOf(rs.getString("health")),
                        rs.getString("raw_signal"),
                        json.read(rs.getString("metrics"), METRICS),
                        Optional.ofNullable(rs.getTimestamp("observed_at"))
                                .map(java.sql.Timestamp::toInstant)
                                .orElse(null)),
                environmentKey);
    }
}
