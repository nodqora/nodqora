package io.nodqora.plugin.api;

import java.util.Objects;

/** One invocation of Discovery: one {@code (plugin, environment)} pair (ADR-0012). */
public record DiscoveryRequest<C>(String environmentKey, C config) {

    public DiscoveryRequest {
        Objects.requireNonNull(environmentKey, "environmentKey");
        Objects.requireNonNull(config, "config");
    }
}
