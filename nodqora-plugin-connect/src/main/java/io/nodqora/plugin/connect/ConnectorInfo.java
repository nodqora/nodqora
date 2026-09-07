// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.connect;

import java.util.Map;
import java.util.Objects;

/**
 * One entry of {@code GET /connectors?expand=info} — what a connector <em>is</em>, on the slow loop.
 *
 * <p>{@code config} is the connector's configuration <b>as the API returns it</b>, which ADR-0038
 * records is unmasked: a connector with an inlined password serves it in plaintext here. That is
 * exactly why nothing downstream of this record forwards the map — {@link ConnectDiscovery}
 * allow-lists three keys out of it by name and reads {@code topics} for edges, and the rest never
 * leaves the plugin.
 *
 * <p>{@code error} carries a per-connector expansion failure on an otherwise successful listing.
 * ADR-0042 splits that from a failed listing deliberately: the first is {@code PARTIAL} naming the
 * connector, the second is {@code FAILED} and touches nothing.
 */
public record ConnectorInfo(String name, String type, Map<String, String> config, String error) {

    public ConnectorInfo {
        Objects.requireNonNull(name, "name");
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public ConnectorInfo(String name, String type, Map<String, String> config) {
        this(name, type, config, null);
    }

    /** {@code sink} and {@code source} are Connect's own words, and the only two it has. */
    public boolean isSink() {
        return "sink".equalsIgnoreCase(type);
    }

    public String config(String key) {
        String value = config.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
