package io.nodqora.plugin.api;

import java.util.List;
import java.util.Objects;

/**
 * One plugin's <em>full snapshot</em> of its scope for one environment — never a delta
 * (ADR-0012, ADR-0049). Plugins do no diffing and hold no previous state.
 */
public record DiscoveryResult(
        List<DiscoveredNode> nodes,
        List<DiscoveredEdge> edges,
        List<DiscoveredOwner> owners,
        List<TypeDescriptor> descriptors,
        Outcome outcome) {

    public DiscoveryResult {
        Objects.requireNonNull(outcome, "outcome");
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        owners = owners == null ? List.of() : List.copyOf(owners);
        descriptors = descriptors == null ? List.of() : List.copyOf(descriptors);
    }

    /** ADR-0046: a {@code FAILED} snapshot carries no entries, and changes nothing in the store. */
    public static DiscoveryResult failed(String cause) {
        return new DiscoveryResult(List.of(), List.of(), List.of(), List.of(), Outcome.failed(cause));
    }
}
