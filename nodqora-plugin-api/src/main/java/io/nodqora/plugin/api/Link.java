// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.api;

import java.util.Objects;

/**
 * A navigation target on a Node (ADR-0007). {@code rel} is an open string with well-known
 * values — {@code repository}, {@code runbook}, {@code docs}, {@code dashboard}, {@code logs},
 * {@code gitops}, {@code workload}, {@code config}, {@code topic}, {@code consumers}.
 */
public record Link(String rel, String label, String url) {

    public Link {
        Objects.requireNonNull(rel, "rel");
        Objects.requireNonNull(url, "url");
    }
}
