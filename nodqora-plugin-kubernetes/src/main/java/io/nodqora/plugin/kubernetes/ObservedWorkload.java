// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One Deployment, StatefulSet or CronJob as the plugin needs it (ADR-0030).
 *
 * <p>This is deliberately not a Kubernetes object. The seam returns the plugin's own shape, which
 * is what makes ADR-0099's recording a small readable file rather than a captured API payload, and
 * what keeps the fabric8 types on one side of one class.
 *
 * <p>{@code creationTimestamp} is load-bearing: it is ADR-0021's tiebreak when two objects resolve
 * to one node key, and it is what makes the winner stable across polls during a rollout.
 * {@code podLabels} is the pod template's labels — what a Service selector matches (ADR-0030).
 *
 * <p>The last three fields are health inputs and every one of them is <b>nullable on purpose</b>
 * (ADR-0034):
 *
 * <ul>
 *   <li>{@code desiredReplicas} is {@code spec.replicas} — <em>declared intent</em>, which is why
 *       {@code kubernetes} is the plugin allowed to emit {@code DISABLED} at all under ADR-0029.
 *       A CronJob has none, so it is {@code null} there and the workload abstains.
 *   <li>{@code readyReplicas} is {@code status.readyReplicas} — what is actually up. The pair is
 *       read as arithmetic rather than through {@code status.conditions}, because
 *       {@code Available=True} holds at 2 of 3 and would erase the fixture's one Kubernetes signal
 *       (ADR-0025). {@code null} means the status has not been populated yet; zero is a reading.
 *   <li>{@code suspend} is a CronJob's {@code spec.suspend}, the other declarative intent.
 * </ul>
 *
 * <p>They are nullable rather than defaulted to zero because the difference matters in the one
 * direction that is destructive: {@code desiredReplicas: 0} is a human turning something off and
 * reads {@code DISABLED}, while "we do not know" must abstain. Defaulting would turn every
 * unreadable object into a deliberate shutdown.
 */
public record ObservedWorkload(
        WorkloadKind kind,
        String namespace,
        String name,
        Instant creationTimestamp,
        Map<String, String> labels,
        Map<String, String> annotations,
        Map<String, String> podLabels,
        Integer desiredReplicas,
        Integer readyReplicas,
        Boolean suspend) {

    public ObservedWorkload {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
        podLabels = podLabels == null ? Map.of() : Map.copyOf(podLabels);
    }

    /** ADR-0035: one cluster per environment, so {@code <namespace>/<name>} is unique. */
    public String reference() {
        return namespace + "/" + name;
    }

    /**
     * ADR-0031's deny-list entry for this object, matched exactly and never as a pattern. The
     * <em>name</em> is compared verbatim — an entry whose case is wrong fails toward an extra node,
     * which is visibly wrong and gets reported, rather than toward a missing one, which ADR-0004
     * cannot tell from drift. Only the kind is folded, because a human writes `StatefulSet`.
     */
    public String denyListEntry() {
        return kind.lowercased() + "/" + name;
    }
}
