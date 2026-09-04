package io.nodqora.plugin.api;

import java.util.Map;
import java.util.Objects;

/**
 * A directed relationship, stored flow-directed (ADR-0002): {@code fromKey → toKey} is always
 * the direction data flows, whatever the relation reads like aloud. A plugin applies the
 * relation's orientation before emitting.
 *
 * <p>Endpoints may be keys the plugin does not own (ADR-0012); the fold materializes a stub node
 * at any endpoint no snapshot carries (ADR-0048).
 */
public record DiscoveredEdge(String fromKey, String toKey, String relation, Map<String, Object> metadata) {

    public DiscoveredEdge {
        Objects.requireNonNull(fromKey, "fromKey");
        Objects.requireNonNull(toKey, "toKey");
        Objects.requireNonNull(relation, "relation");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public DiscoveredEdge(String fromKey, String toKey, String relation) {
        this(fromKey, toKey, relation, Map.of());
    }
}
