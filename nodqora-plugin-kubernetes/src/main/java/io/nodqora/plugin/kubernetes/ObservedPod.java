// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

import java.util.Map;
import java.util.Objects;

/**
 * One pod, read <b>only</b> as a readiness input for a {@code pods} backing (ADR-0148).
 *
 * <p>ADR-0005 puts pods out of the graph — "a Deployment, a pod and a Service are backings of a
 * Node, not Nodes" — and that is untouched here. A pod never becomes a node, never becomes a
 * backing, and is never named in {@code rawSignal}: the backing names the <em>selector</em>, and the
 * pods it matches are counted and discarded. Discovery does not read this list at all.
 *
 * <p>{@code phase} is the raw {@code status.phase} string rather than an enum, because the plugin
 * asks it one question — is this pod still a live member of the set — and a closed enum would have
 * to be extended for a phase Kubernetes adds while the question stayed the same.
 *
 * <p>{@code ready} is the {@code Ready} condition being {@code True}, which is the same thing
 * {@code status.readyReplicas} counts on a workload. So a node backed by a Deployment and a node
 * backed by a selector over that Deployment's pods report the same numbers by the same definition.
 */
public record ObservedPod(
        String namespace, String name, Map<String, String> labels, String phase, boolean ready, boolean terminating) {

    private static final String SUCCEEDED = "Succeeded";

    private static final String FAILED = "Failed";

    public ObservedPod {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }

    /**
     * A pod the set should still be counting: neither terminal nor on its way out.
     *
     * <p>One rule rather than three, and it is the rule that makes a selector safe over any owner
     * kind. A completed Job's pods sit in {@code Succeeded} forever and would otherwise drag a node
     * to {@code UNHEALTHY} for having finished successfully — ADR-0030's reason for refusing to
     * discover Jobs at all, arriving here from the other direction. A {@code Failed} pod is terminal
     * in the same way; a pod that is crash-looping is {@code Running} and not ready, which is the
     * reading that should alarm and does.
     *
     * <p>Terminating pods are excluded so that a rolling update does not inflate the count of what
     * the owner is asking for. The one it does <em>not</em> exclude is a surge pod, which is
     * genuinely wanted right now.
     */
    public boolean live() {
        return !terminating && !SUCCEEDED.equals(phase) && !FAILED.equals(phase);
    }
}
