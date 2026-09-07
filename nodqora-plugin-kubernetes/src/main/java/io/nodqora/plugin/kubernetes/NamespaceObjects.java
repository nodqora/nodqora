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
 */
public record NamespaceObjects(
        List<ObservedWorkload> workloads, List<ObservedService> services, List<ObservedIngress> ingresses) {

    public NamespaceObjects {
        workloads = workloads == null ? List.of() : List.copyOf(workloads);
        services = services == null ? List.of() : List.copyOf(services);
        ingresses = ingresses == null ? List.of() : List.copyOf(ingresses);
    }
}
