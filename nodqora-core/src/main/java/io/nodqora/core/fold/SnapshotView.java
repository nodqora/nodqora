package io.nodqora.core.fold;

import io.nodqora.plugin.api.DiscoveredEdge;
import io.nodqora.plugin.api.DiscoveredNode;
import io.nodqora.plugin.api.DiscoveredOwner;
import java.util.List;
import java.util.Objects;

/**
 * One plugin's stored snapshot for one environment, as the fold reads it.
 *
 * <p>Descriptors are deliberately absent: they are stored as entries like everything else, but they
 * are global and are served as a read-time projection over the whole store (ADR-0077), so they are
 * not part of an environment's fold.
 */
public record SnapshotView(
        String pluginId,
        List<DiscoveredNode> nodes,
        List<DiscoveredEdge> edges,
        List<DiscoveredOwner> owners) {

    public SnapshotView {
        Objects.requireNonNull(pluginId, "pluginId");
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        owners = owners == null ? List.of() : List.copyOf(owners);
    }
}
