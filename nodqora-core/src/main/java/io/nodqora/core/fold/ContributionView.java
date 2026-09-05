// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.fold;

import io.nodqora.plugin.api.Health;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One plugin's retained observation of one node, as the state fold reads it (ADR-0072).
 *
 * <p>The fast half's counterpart to {@link SnapshotView}, and smaller for a structural reason: a
 * discovery snapshot is a plugin's whole opinion about an environment and has to be read as a unit,
 * while a contribution is already per node. The fold groups these by {@code nodeId}; nothing here
 * needs to know which snapshot it arrived in.
 *
 * <p>{@code health} is never {@code UNKNOWN} in practice: an abstention is an omission and is
 * dropped before it reaches the store (ADR-0104). It is not forbidden by this type, because the fold
 * enforces the same rule itself rather than relying on its caller — a pure function that is only
 * correct given a well-formed input is a trap for the next reader.
 */
public record ContributionView(
        long nodeId,
        String pluginId,
        Health health,
        String rawSignal,
        Map<String, Object> metrics,
        Instant observedAt) {

    public ContributionView {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(observedAt, "observedAt");
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
}
