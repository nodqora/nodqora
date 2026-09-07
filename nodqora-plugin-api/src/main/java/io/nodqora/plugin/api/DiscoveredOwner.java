// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.api;

import java.util.Objects;

/** A team (ADR-0007). Environment-scoped discovery output, folded exactly like a node (ADR-0049). */
public record DiscoveredOwner(String key, String displayName, String channel, String onCall) {

    public DiscoveredOwner {
        Objects.requireNonNull(key, "key");
    }
}
