// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

import java.util.List;
import java.util.Objects;

/**
 * An Ingress, which reaches a workload only through the Services it names (ADR-0030):
 *
 * <pre>Ingress --(backend service name)--&gt; Service --(selector ⊇ pod labels)--&gt; workload</pre>
 *
 * <p>{@code services} is every backend service name on the object — the default backend and every
 * rule path — because which rule matched is a request-time fact and this is a topology read.
 */
public record ObservedIngress(String namespace, String name, List<String> services) {

    public ObservedIngress {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        services = services == null ? List.of() : List.copyOf(services);
    }

    public String reference() {
        return namespace + "/" + name;
    }
}
