package io.nodqora.plugin.kubernetes;

import java.util.Map;
import java.util.Objects;

/**
 * A Service, which attaches to a node by selector and never becomes one (ADR-0030).
 *
 * <p>An empty {@code selector} is a headless or {@code ExternalName} Service: it selects nothing,
 * so it attaches to nothing. Selector matching is the one Kubernetes inference that survives
 * ADR-0033, and it produces backing attachment rather than an edge — a Service and its Deployment
 * are backings of the <em>same</em> node.
 */
public record ObservedService(String namespace, String name, Map<String, String> selector) {

    public ObservedService {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        selector = selector == null ? Map.of() : Map.copyOf(selector);
    }

    public String reference() {
        return namespace + "/" + name;
    }

    /** Kubernetes selector semantics: every selector entry must be present on the pod labels. */
    public boolean selects(ObservedWorkload workload) {
        return !selector.isEmpty()
                && selector.entrySet().stream()
                        .allMatch(entry -> entry.getValue().equals(workload.podLabels().get(entry.getKey())));
    }
}
