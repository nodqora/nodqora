// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

import java.util.Locale;

/**
 * The three kinds that become nodes (ADR-0030). Nothing else does: a bare Job is a <em>run</em>
 * rather than a component and would read as drift when it is garbage-collected between two polls,
 * a DaemonSet is infrastructure-shaped, and a namespace is scope.
 *
 * <p>The enum sits at the outbound seam rather than inside the fold, because "which kinds become
 * nodes" is a statement about <em>what we ask the cluster for</em>. Services and Ingresses are
 * listed too and attach as backings; they are not workloads and are not here.
 */
public enum WorkloadKind {
    DEPLOYMENT,
    STATEFULSET,
    CRONJOB;

    /** The spelling used in {@code backings[].kind} and in ADR-0031's {@code kind/name} deny-list. */
    public String lowercased() {
        return name().toLowerCase(Locale.ROOT);
    }
}
