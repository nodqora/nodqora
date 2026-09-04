package io.nodqora.core.store;

import com.fasterxml.jackson.core.type.TypeReference;
import io.nodqora.plugin.api.Health;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The fast half's read path (ADR-0003, ADR-0028).
 *
 * <p>A node nobody observes has no {@code node_state} row at all, and the outer join synthesizes
 * {@code UNKNOWN} / {@code rawSignal: null} / {@code metrics: {}} / {@code observedAt: null}. That is
 * arithmetic rather than a rule anyone wrote, and it is why this query needs no branch for the four
 * fixture nodes that are permanently {@code UNKNOWN}.
 *
 * <p>In slice 1 <em>every</em> node takes that path: {@code yaml} declares no Health capability, so
 * nothing writes a contribution and nothing writes this table. Grey is the honest rendering, not a
 * placeholder — and the frontend's health path does not change when contributions arrive, only its
 * source does.
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
