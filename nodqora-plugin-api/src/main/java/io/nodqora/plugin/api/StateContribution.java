// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.api;

import java.util.Map;
import java.util.Objects;

/**
 * One plugin's observation of one node (ADR-0013). A contribution, not a NodeState: the state
 * engine composes the single row from several of these (ADR-0024, ADR-0072).
 */
public record StateContribution(Health health, String rawSignal, Map<String, Object> metrics) {

    public StateContribution {
        Objects.requireNonNull(health, "health");
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
}
