package io.nodqora.core.store;

import com.fasterxml.jackson.core.type.TypeReference;
import io.nodqora.core.graph.FoldedEdge;
import io.nodqora.core.graph.FoldedGraph;
import io.nodqora.core.graph.FoldedNode;
import io.nodqora.core.graph.FoldedOwner;
import io.nodqora.core.graph.GraphRecords.EdgeRecord;
import io.nodqora.core.graph.GraphRecords.GraphRecord;
import io.nodqora.core.graph.GraphRecords.NodeRecord;
import io.nodqora.core.graph.GraphRecords.OwnerRecord;
import io.nodqora.core.graph.Keys;
import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.Link;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The derived cache: {@code node}, {@code edge} and {@code owner} rows, written only by the fold.
 *
 * <p>Its whole job beyond storage is ADR-0050's rule: <b>{@code updatedAt} moves iff the folded row
 * differs from the stored row in any field except {@code id}, {@code discoveredAt} and
 * {@code updatedAt}.</b> The fold has already canonically ordered every collection, and
 * {@link Json#canonical} makes a stored value and a folded value comparable across the {@code jsonb}
 * round trip — without both, every poll is a spurious diff and {@code updatedAt} degenerates into a
 * poll clock, silently, in the direction where nothing looks broken.
 *
 * <p>{@code discoveredAt} is when the fold created the row. A node that leaves and returns gets a
 * new one, and a new surrogate id, because ADR-0043 keeps no tombstone.
 */
@Component
public class GraphStore {

    private static final TypeReference<List<Link>> LINKS = new TypeReference<>() {};
    private static final TypeReference<List<Backing>> BACKINGS = new TypeReference<>() {};
    private static final TypeReference<List<String>> SOURCES = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> METADATA = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final Json json;

    public GraphStore(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Recomputes the whole environment. The caller holds the environment row lock (ADR-0075). */
    public void write(String environmentKey, FoldedGraph graph, Instant now) {
        writeNodes(environmentKey, graph.nodes(), now);
        writeEdges(environmentKey, graph.edges(), now);
        writeOwners(environmentKey, graph.owners(), now);
    }

    // ---------------------------------------------------------------- nodes

    /** A stored row reduced to its identity and the fields ADR-0050 compares. */
    private record StoredRow(long id, Object[] compared) {}

    private void writeNodes(String environmentKey, List<FoldedNode> nodes, Instant now) {
        Map<String, StoredRow> stored = new LinkedHashMap<>();
        jdbc.query(
                """
                select id, key, type, display_name, description, owner_key,
                       links, backings, metadata, sources
                from node where environment_key = ?
                """,
                rs -> {
                    stored.put(
                            Keys.folded(rs.getString("key")),
                            new StoredRow(rs.getLong("id"), new Object[] {
                                rs.getString("key"),
                                rs.getString("type"),
                                rs.getString("display_name"),
                                rs.getString("description"),
                                rs.getString("owner_key"),
                                json.canonical(rs.getString("links")),
                                json.canonical(rs.getString("backings")),
                                json.canonical(rs.getString("metadata")),
                                json.canonical(rs.getString("sources")),
                            }));
                },
                environmentKey);

        for (FoldedNode node : nodes) {
            StoredRow prior = stored.remove(Keys.folded(node.key()));
            Object[] folded = new Object[] {
                node.key(),
                node.type(),
                node.displayName(),
                node.description(),
                node.ownerKey(),
                json.canonical(node.links()),
                json.canonical(node.backings()),
                json.canonical(node.metadata()),
                json.canonical(node.sources()),
            };
            if (prior == null) {
                jdbc.update(
                        """
                        insert into node (environment_key, key, type, display_name, description, owner_key,
                                          links, backings, metadata, sources, discovered_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                        """,
                        environmentKey,
                        node.key(),
                        node.type(),
                        node.displayName(),
                        node.description(),
                        node.ownerKey(),
                        json.write(node.links()),
                        json.write(node.backings()),
                        json.write(node.metadata()),
                        json.write(node.sources()),
                        Timestamp.from(now),
                        Timestamp.from(now));
            } else if (!Objects.deepEquals(prior.compared(), folded)) {
                jdbc.update(
                        """
                        update node set key = ?, type = ?, display_name = ?, description = ?, owner_key = ?,
                                        links = ?::jsonb, backings = ?::jsonb, metadata = ?::jsonb,
                                        sources = ?::jsonb, updated_at = ?
                        where id = ?
                        """,
                        node.key(),
                        node.type(),
                        node.displayName(),
                        node.description(),
                        node.ownerKey(),
                        json.write(node.links()),
                        json.write(node.backings()),
                        json.write(node.metadata()),
                        json.write(node.sources()),
                        Timestamp.from(now),
                        prior.id());
            }
        }
        // Whatever the fold did not produce is gone (ADR-0047), which cascades its NodeState with it.
        deleteById("node", stored.values().stream().map(StoredRow::id).toList());
    }

    // ---------------------------------------------------------------- edges

    private void writeEdges(String environmentKey, List<FoldedEdge> edges, Instant now) {
        Map<String, StoredRow> stored = new LinkedHashMap<>();
        jdbc.query(
                """
                select id, from_key, to_key, relation, metadata, sources
                from edge where environment_key = ?
                """,
                rs -> {
                    stored.put(
                            edgeIdentity(rs.getString("from_key"), rs.getString("to_key"), rs.getString("relation")),
                            new StoredRow(rs.getLong("id"), new Object[] {
                                rs.getString("from_key"),
                                rs.getString("to_key"),
                                json.canonical(rs.getString("metadata")),
                                json.canonical(rs.getString("sources")),
                            }));
                },
                environmentKey);

        for (FoldedEdge edge : edges) {
            StoredRow prior = stored.remove(edgeIdentity(edge.fromKey(), edge.toKey(), edge.relation()));
            Object[] folded = new Object[] {
                edge.fromKey(), edge.toKey(), json.canonical(edge.metadata()), json.canonical(edge.sources()),
            };
            if (prior == null) {
                jdbc.update(
                        """
                        insert into edge (environment_key, from_key, to_key, relation, metadata, sources,
                                          discovered_at, updated_at)
                        values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                        """,
                        environmentKey,
                        edge.fromKey(),
                        edge.toKey(),
                        edge.relation(),
                        json.write(edge.metadata()),
                        json.write(edge.sources()),
                        Timestamp.from(now),
                        Timestamp.from(now));
            } else if (!Objects.deepEquals(prior.compared(), folded)) {
                jdbc.update(
                        """
                        update edge set from_key = ?, to_key = ?, metadata = ?::jsonb, sources = ?::jsonb,
                                        updated_at = ?
                        where id = ?
                        """,
                        edge.fromKey(),
                        edge.toKey(),
                        json.write(edge.metadata()),
                        json.write(edge.sources()),
                        Timestamp.from(now),
                        prior.id());
            }
        }
        deleteById("edge", stored.values().stream().map(StoredRow::id).toList());
    }

    /** ADR-0045: endpoints case-folded, the relation matched exactly. */
    private static String edgeIdentity(String fromKey, String toKey, String relation) {
        return Keys.folded(fromKey) + " " + Keys.folded(toKey) + " " + relation;
    }

    // ---------------------------------------------------------------- owners

    private void writeOwners(String environmentKey, List<FoldedOwner> owners, Instant now) {
        Map<String, StoredRow> stored = new LinkedHashMap<>();
        jdbc.query(
                "select id, key, display_name, channel, on_call from owner where environment_key = ?",
                rs -> {
                    stored.put(
                            Keys.folded(rs.getString("key")),
                            new StoredRow(rs.getLong("id"), new Object[] {
                                rs.getString("key"),
                                rs.getString("display_name"),
                                rs.getString("channel"),
                                rs.getString("on_call"),
                            }));
                },
                environmentKey);

        for (FoldedOwner owner : owners) {
            StoredRow prior = stored.remove(Keys.folded(owner.key()));
            Object[] folded = new Object[] {owner.key(), owner.displayName(), owner.channel(), owner.onCall()};
            if (prior == null) {
                jdbc.update(
                        """
                        insert into owner (environment_key, key, display_name, channel, on_call,
                                           discovered_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?)
                        """,
                        environmentKey,
                        owner.key(),
                        owner.displayName(),
                        owner.channel(),
                        owner.onCall(),
                        Timestamp.from(now),
                        Timestamp.from(now));
            } else if (!Objects.deepEquals(prior.compared(), folded)) {
                jdbc.update(
                        """
                        update owner set key = ?, display_name = ?, channel = ?, on_call = ?, updated_at = ?
                        where id = ?
                        """,
                        owner.key(),
                        owner.displayName(),
                        owner.channel(),
                        owner.onCall(),
                        Timestamp.from(now),
                        prior.id());
            }
        }
        deleteById("owner", stored.values().stream().map(StoredRow::id).toList());
    }

    private void deleteById(String table, List<Long> ids) {
        if (!ids.isEmpty()) {
            jdbc.batchUpdate(
                    "delete from " + table + " where id = ?",
                    ids.stream().map(id -> new Object[] {id}).toList());
        }
    }

    // ---------------------------------------------------------------- reading

    public GraphRecord read(String environmentKey) {
        List<NodeRecord> nodes = jdbc.query(
                """
                select id, key, type, display_name, description, owner_key,
                       links, backings, metadata, sources, discovered_at, updated_at
                from node where environment_key = ? order by lower(btrim(key))
                """,
                (rs, row) -> new NodeRecord(
                        rs.getLong("id"),
                        rs.getString("key"),
                        rs.getString("type"),
                        rs.getString("display_name"),
                        rs.getString("description"),
                        rs.getString("owner_key"),
                        json.read(rs.getString("links"), LINKS),
                        json.read(rs.getString("backings"), BACKINGS),
                        json.read(rs.getString("metadata"), METADATA),
                        json.read(rs.getString("sources"), SOURCES),
                        rs.getTimestamp("discovered_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                environmentKey);

        List<EdgeRecord> edges = jdbc.query(
                """
                select from_key, to_key, relation, metadata, sources, discovered_at, updated_at
                from edge where environment_key = ?
                order by lower(btrim(from_key)), lower(btrim(to_key)), relation
                """,
                (rs, row) -> new EdgeRecord(
                        rs.getString("from_key"),
                        rs.getString("to_key"),
                        rs.getString("relation"),
                        json.read(rs.getString("metadata"), METADATA),
                        json.read(rs.getString("sources"), SOURCES),
                        rs.getTimestamp("discovered_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                environmentKey);

        List<OwnerRecord> owners = jdbc.query(
                """
                select key, display_name, channel, on_call from owner
                where environment_key = ? order by lower(btrim(key))
                """,
                (rs, row) -> new OwnerRecord(
                        rs.getString("key"),
                        rs.getString("display_name"),
                        rs.getString("channel"),
                        rs.getString("on_call")),
                environmentKey);

        return new GraphRecord(nodes, edges, owners);
    }

    /** Every node key in the environment, in canonical order — {@code /state} carries all of them. */
    public List<String> nodeKeys(String environmentKey) {
        return new ArrayList<>(jdbc.queryForList(
                "select key from node where environment_key = ? order by lower(btrim(key))",
                String.class,
                environmentKey));
    }
}
