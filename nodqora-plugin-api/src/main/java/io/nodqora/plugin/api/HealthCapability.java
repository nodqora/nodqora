// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.api;

import java.util.List;
import java.util.Map;

/**
 * Produces runtime state (ADR-0010). Batched, because the underlying APIs are batched (ADR-0013):
 * the plugin is handed exactly the nodes carrying a backing of its own.
 *
 * <p>{@code yaml} never implements this and that is a decision rather than an omission (ADR-0029):
 * a topology file declaring a node {@code DISABLED} would give it a health value that is static
 * forever, wins every collapse, and quietly hides real failures until somebody edits a file. A
 * plugin that declares no Health capability leaves its nodes {@code UNKNOWN}, which is the honest
 * answer rather than a placeholder (ADR-0011, ADR-0028).
 *
 * <p>A contribution may be <em>omitted</em> for a node the plugin could not read. An abstention is
 * an omission (ADR-0104), and returning {@code UNKNOWN} for something actually observed deletes the
 * plugin's own vote under ADR-0024 — so it means "I was not asked" or "I could not look", and
 * nothing else.
 */
public interface HealthCapability<C> extends Plugin<C> {

    HealthResult observe(HealthRequest<C> request);

    record HealthRequest<C>(String environmentKey, List<ObservableNode> nodes, C config) {}

    /** Just enough of a folded node for a plugin to find its own backings. */
    record ObservableNode(String key, List<Backing> backings) {}

    record HealthResult(Map<String, StateContribution> contributions, Outcome outcome) {}
}
