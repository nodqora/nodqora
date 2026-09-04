package io.nodqora.core.graph;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.Link;
import java.util.List;
import java.util.Map;

/**
 * A node as the fold computes it — everything on ADR-0003's slow side, and nothing else.
 *
 * <p>There is no {@code stub} flag: a node no snapshot carries as an entry is simply this record
 * with every scalar null and every collection empty (ADR-0048).
 *
 * <p>{@code discoveredAt} and {@code updatedAt} are deliberately absent. They are not a function of
 * the inputs — they are a function of the inputs <em>and the stored row</em> (ADR-0050), so they are
 * applied where the comparison happens and the fold itself stays pure and order-independent.
 */
public record FoldedNode(
        String key,
        String type,
        String displayName,
        String description,
        String ownerKey,
        List<Link> links,
        List<Backing> backings,
        Map<String, Object> metadata,
        List<String> sources) {}
