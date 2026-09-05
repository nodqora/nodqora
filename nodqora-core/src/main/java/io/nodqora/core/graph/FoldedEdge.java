// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.graph;

import java.util.List;
import java.util.Map;

/**
 * An edge as the fold computes it. Its identity is the full tuple
 * {@code (environmentKey, fromKey, toKey, relation)} (ADR-0045), so it has no contestable scalar at
 * all and edge merge is set union.
 */
public record FoldedEdge(
        String fromKey, String toKey, String relation, Map<String, Object> metadata, List<String> sources) {}
