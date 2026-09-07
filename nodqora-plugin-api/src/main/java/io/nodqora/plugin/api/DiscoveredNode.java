// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One plugin's opinion about one node (ADR-0012).
 *
 * <p>The key is final — the plugin resolved identity itself (ADR-0020). Every other scalar is
 * nullable and {@code null} means <em>no opinion</em>, never <em>empty</em>: a value disappears
 * when the snapshot carrying it stops carrying it (ADR-0043).
 *
 * <p>{@code metadata} is this plugin's <em>own</em> namespace, not the node's plugin-keyed map.
 * The fold files it under this plugin's id (ADR-0006), so a plugin cannot write into another's.
 */
public record DiscoveredNode(
        String key,
        String type,
        String displayName,
        String description,
        String ownerKey,
        List<Link> links,
        List<Backing> backings,
        Map<String, Object> metadata) {

    public DiscoveredNode {
        Objects.requireNonNull(key, "key");
        links = links == null ? List.of() : List.copyOf(links);
        backings = backings == null ? List.of() : List.copyOf(backings);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** A node a plugin names but has nothing to say about — ADR-0063's verb-only stanza. */
    public static DiscoveredNode ofKey(String key) {
        return new DiscoveredNode(key, null, null, null, null, List.of(), List.of(), Map.of());
    }
}
