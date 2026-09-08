// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

import java.util.List;

/**
 * Everything one namespace holds that the plugin can use — the whole of its dependency on the
 * outside world for one poll (ADR-0012, ADR-0099).
 *
 * <p>That it is <em>one call returning one set of objects</em> is not a testing convenience: a
 * plugin returns a full stateless snapshot per poll, so recording this is recording the thing
 * ADR-0012 already says the plugin is.
 *
 * <p>{@code pods} is the odd one out and is the only list <b>discovery never reads</b>. Pods are out
 * of the graph under ADR-0005 and stay out; they are here because a {@code pods} backing counts them
 * for readiness (ADR-0148), and counting them means the health half has to have them. Listing them
 * here rather than behind a selector-shaped call is what keeps this record "a namespace" — the
 * recorded seam does not learn the configuration being replayed.
 */
public record NamespaceObjects(
        List<ObservedWorkload> workloads,
        List<ObservedService> services,
        List<ObservedIngress> ingresses,
        List<ObservedPod> pods) {

    public NamespaceObjects {
        workloads = workloads == null ? List.of() : List.copyOf(workloads);
        services = services == null ? List.of() : List.copyOf(services);
        ingresses = ingresses == null ? List.of() : List.copyOf(ingresses);
        pods = pods == null ? List.of() : List.copyOf(pods);
    }

    /**
     * The three lists discovery works from. A namespace with no pods worth counting is the ordinary
     * case — nothing in it carries a {@code pods} backing — so a test or a recording that has no
     * opinion about pods says so by omission rather than by writing an empty list.
     */
    public NamespaceObjects(
            List<ObservedWorkload> workloads, List<ObservedService> services, List<ObservedIngress> ingresses) {
        this(workloads, services, ingresses, List.of());
    }
}
