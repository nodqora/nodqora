package io.nodqora.plugin.api;

import java.util.List;
import java.util.Map;

/**
 * Produces runtime state (ADR-0010). Batched, because the underlying APIs are batched (ADR-0013):
 * the plugin is handed exactly the nodes carrying a backing of its own.
 *
 * <p>No MVP plugin implements this until slice 3; {@code yaml} never will. A plugin that declares
 * no Health capability leaves its nodes {@code UNKNOWN}, which is the honest answer rather than a
 * placeholder (ADR-0011, ADR-0028).
 */
public interface HealthCapability<C> extends Plugin<C> {

    HealthResult observe(HealthRequest<C> request);

    record HealthRequest<C>(String environmentKey, List<ObservableNode> nodes, C config) {}

    /** Just enough of a folded node for a plugin to find its own backings. */
    record ObservableNode(String key, List<Backing> backings) {}

    record HealthResult(Map<String, StateContribution> contributions, Outcome outcome) {}
}
