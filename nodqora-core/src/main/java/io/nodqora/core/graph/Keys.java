// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.graph;

import java.util.Locale;

/**
 * ADR-0020: a key is stored verbatim and trimmed, but uniqueness and merge lookup are case-folded.
 * That is what lets two plugins mint {@code payments-enricher} independently and land on one row.
 *
 * <p>The same folding applies to owner keys (ADR-0071) and to edge endpoints (ADR-0045) — an edge
 * naming {@code Payments-API} must reach the node keyed {@code payments-api}, or the fold
 * materializes a second stub differing only in case. A {@code relation} is matched <em>exactly</em>:
 * it is a vocabulary id from a registry of built-ins, not discovered data.
 */
public final class Keys {

    private Keys() {}

    public static String verbatim(String key) {
        return key == null ? null : key.trim();
    }

    public static String folded(String key) {
        return key == null ? null : key.trim().toLowerCase(Locale.ROOT);
    }
}
