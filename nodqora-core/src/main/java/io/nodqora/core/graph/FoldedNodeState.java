// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.graph;

import io.nodqora.plugin.api.Health;
import java.time.Instant;
import java.util.Map;

/**
 * A NodeState as the fold computes it — everything on ADR-0003's fast side, and nothing else.
 *
 * <p>Keyed by {@code nodeId} rather than by node key, because its inputs are (ADR-0072). A node
 * that leaves and returns gets a new surrogate id and therefore a fresh row, reading {@code UNKNOWN}
 * for up to one fast-loop interval rather than instantly re-inheriting a verdict about a previous
 * incarnation.
 *
 * <p>There is no {@code UNKNOWN} row. A node whose contributions all abstained has no row at all,
 * and ADR-0028's outer join synthesizes {@code UNKNOWN} / {@code null} / <code>{}</code> /
 * {@code null} — the same four values, by arithmetic rather than by a second code path (ADR-0104).
 */
public record FoldedNodeState(
        long nodeId, Health health, String rawSignal, Map<String, Object> metrics, Instant observedAt) {}
