package io.nodqora.core.graph;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.Link;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The folded rows as the read path sees them: a {@link FoldedNode} plus the two timestamps the
 * writer supplied by comparing it against what was already stored (ADR-0050).
 *
 * <p>{@code updatedAt} means <em>the topology changed</em> and nothing else. It is not a write
 * clock: the fold rewrites every row in an environment on every poll, so the write path cannot be
 * trusted to imply a change and the diff is what carries the meaning.
 */
public final class GraphRecords {

    private GraphRecords() {}

    public record NodeRecord(
            long id,
            String key,
            String type,
            String displayName,
            String description,
            String ownerKey,
            List<Link> links,
            List<Backing> backings,
            Map<String, Object> metadata,
            List<String> sources,
            Instant discoveredAt,
            Instant updatedAt) {}

    public record EdgeRecord(
            String fromKey,
            String toKey,
            String relation,
            Map<String, Object> metadata,
            List<String> sources,
            Instant discoveredAt,
            Instant updatedAt) {}

    public record OwnerRecord(String key, String displayName, String channel, String onCall) {}

    public record GraphRecord(List<NodeRecord> nodes, List<EdgeRecord> edges, List<OwnerRecord> owners) {}
}
