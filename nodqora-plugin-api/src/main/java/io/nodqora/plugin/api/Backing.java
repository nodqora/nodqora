// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.api;

import java.util.Objects;

/**
 * A physical object behind a Node (ADR-0005, ADR-0013).
 *
 * <p>{@code plugin} names the plugin whose <em>technology domain</em> the object belongs to,
 * never the plugin that discovered it — provenance is the node's {@code sources[]}. Backings
 * are the routing table for health: a plugin is asked to observe exactly the nodes carrying a
 * backing of its own.
 */
public record Backing(String plugin, String kind, String reference) {

    public Backing {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reference, "reference");
    }
}
