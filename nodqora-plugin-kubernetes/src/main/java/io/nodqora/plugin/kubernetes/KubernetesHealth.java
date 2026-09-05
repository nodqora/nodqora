package io.nodqora.plugin.kubernetes;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.Health;
import io.nodqora.plugin.api.HealthCapability.HealthResult;
import io.nodqora.plugin.api.HealthCapability.ObservableNode;
import io.nodqora.plugin.api.Outcome;
import io.nodqora.plugin.api.StateContribution;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One environment's Kubernetes observation: list the enumerated namespaces, find each routed node's
 * own workload objects, and normalize them.
 *
 * <p><b>Readiness arithmetic, not {@code status.conditions}</b> (ADR-0025). Reading {@code Available}
 * would suppress the flap an ordinary rolling update causes — but {@code Available=True} holds at 2
 * of 3 replicas, which renders the fixture's {@code enricher-v2} {@code HEALTHY} and erases the one
 * Kubernetes signal the whole fixture is built around. A tool whose job is "what does this look like
 * right now" says <em>two of three</em> when two of three are up. The deploy flap is honest and
 * self-clearing, and {@code rawSignal} carries the counts.
 *
 * <p><b>This is the plugin that may say {@code DISABLED}</b> (ADR-0029, ADR-0034). Intent here is
 * <em>declarative</em>: {@code spec.replicas: 0} and {@code spec.suspend: true} sit in the object's
 * own spec, written by a human or by an autoscaler acting on one, rather than being inferred from an
 * absence of activity. That is why {@code kafka} may never emit it — an {@code EMPTY} consumer group
 * is the exact state of a scaled-down consumer and a crashed one — and why this plugin may.
 *
 * <p>Three things it deliberately does <em>not</em> do:
 *
 * <ul>
 *   <li><b>Observe Service and Ingress backings.</b> They have no readiness concept and are inert
 *       for health, so {@code payments-api}'s three backings yield one workload observation.
 *   <li><b>Report a vanished object as {@code UNHEALTHY}.</b> Discovery runs at five minutes and
 *       this at thirty seconds, so a deliberately deleted Deployment would otherwise paint ten
 *       cycles of red for something a human removed. There is nothing there to observe, which is the
 *       "could not look" branch of {@code UNKNOWN} (ADR-0026).
 *   <li><b>Return {@code UNKNOWN} for anything it actually observed.</b> Under ADR-0024 an
 *       abstention is discarded, so returning it for an observed-but-awkward state deletes this
 *       plugin's own vote (ADR-0029).
 * </ul>
 */
class KubernetesHealth {

    private static final Logger log = LoggerFactory.getLogger(KubernetesHealth.class);

    /** ADR-0028's allow-list for this plugin, enumerated by name and never "whatever we read". */
    private static final String DESIRED_REPLICAS = "desiredReplicas";

    private static final String READY_REPLICAS = "readyReplicas";

    /**
     * ADR-0034: only workload backings contribute — a Service and an Ingress have no readiness to
     * report and are inert for health. Derived from the enum rather than listed, so a kind added to
     * ADR-0030 cannot be silently left out of health while being discovered.
     */
    private static final Set<String> WORKLOAD_KINDS = Arrays.stream(WorkloadKind.values())
            .map(WorkloadKind::lowercased)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final KubernetesApi api;

    KubernetesHealth(KubernetesApi api) {
        this.api = api;
    }

    HealthResult observe(String environmentKey, List<ObservableNode> nodes, KubernetesConfig config) {
        if (nodes.isEmpty()) {
            // Nothing carries a `kubernetes` backing, so there is nothing this plugin could look at
            // and no reason to spend a cluster round trip finding that out. COMPLETE with no
            // contributions is the truthful answer and clears anything stale.
            return new HealthResult(Map.of(), Outcome.complete());
        }

        Map<String, ObservedWorkload> byReference = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        int unreachable = 0;

        for (String namespace : config.namespaces()) {
            try {
                api.list(config, namespace)
                        .workloads()
                        .forEach(workload -> byReference.put(workload.reference(), workload));
            } catch (RuntimeException e) {
                unreachable++;
                reasons.add("namespace %s could not be listed: %s".formatted(namespace, message(e)));
            }
        }

        // ADR-0026: FAILED when the API server is unreachable, PARTIAL when some list calls
        // succeeded and others did not. A FAILED run leaves the store untouched (ADR-0046), so the
        // last good readings stand and go visibly stale rather than the whole environment flipping
        // grey because one poll blinked.
        if (unreachable == config.namespaces().size()) {
            return new HealthResult(Map.of(), Outcome.failed(String.join("; ", reasons)));
        }

        Map<String, StateContribution> contributions = new LinkedHashMap<>();
        for (ObservableNode node : nodes) {
            contribution(node, byReference).ifPresent(observed -> contributions.put(node.key(), observed));
        }

        log.debug(
                "health {}/{}: {} node(s) routed, {} observed",
                environmentKey,
                KubernetesDiscovery.PLUGIN_ID,
                nodes.size(),
                contributions.size());

        return new HealthResult(
                contributions, reasons.isEmpty() ? Outcome.complete() : Outcome.partial(reasons));
    }

    /**
     * ADR-0034: several workload backings on one node collapse with <b>ADR-0024's own algorithm</b>,
     * inside the plugin. One collapse applied at two levels, and no second rule to keep in step —
     * which is why the node ADR-0034 warns about behaves correctly for free: scaling the shared
     * {@code kafka-connect} StatefulSet to zero contributes {@code DISABLED} to every node it backs,
     * and {@code DISABLED} wins outright at both levels.
     *
     * <p>Returns nothing when every workload abstained. An abstention is an omission (ADR-0104), so
     * a node this plugin could not read has no contribution rather than an {@code UNKNOWN} one that
     * the fold would discard a step later anyway.
     */
    private java.util.Optional<StateContribution> contribution(
            ObservableNode node, Map<String, ObservedWorkload> byReference) {
        List<ObservedWorkload> observed = node.backings().stream()
                .filter(backing -> KubernetesDiscovery.PLUGIN_ID.equals(backing.plugin()))
                .filter(backing -> WORKLOAD_KINDS.contains(backing.kind()))
                .map(Backing::reference)
                // A backing whose object has vanished abstains: deletion is discovery's to read from
                // its own snapshot, never encoded as health.
                .map(byReference::get)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(ObservedWorkload::reference))
                .toList();

        List<Health> healths = observed.stream().map(KubernetesHealth::health).toList();
        Health collapsed = Health.collapse(healths);
        if (collapsed == Health.UNKNOWN) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(
                new StateContribution(collapsed, rawSignal(observed), metrics(observed)));
    }

    /**
     * ADR-0025's table, with ADR-0034's two declarative-intent rules in front of it:
     *
     * <table>
     *   <tr><th>object</th><th>health</th></tr>
     *   <tr><td>{@code spec.suspend: true} on a CronJob</td><td>{@code DISABLED}</td></tr>
     *   <tr><td>a running CronJob</td><td>abstains — no replica concept, and ADR-0030 does not
     *       discover its Jobs</td></tr>
     *   <tr><td>{@code spec.replicas == 0}</td><td>{@code DISABLED}</td></tr>
     *   <tr><td>all desired ready</td><td>{@code HEALTHY}</td></tr>
     *   <tr><td>some ready</td><td>{@code DEGRADED}</td></tr>
     *   <tr><td>none ready</td><td>{@code UNHEALTHY}</td></tr>
     * </table>
     *
     * <p>The zero check comes before the arithmetic because the arithmetic has no honest answer
     * there: nought of nought ready is both "all of them" and "none of them", and ADR-0025 is
     * explicit that all/some/none applies only when {@code desired > 0}.
     */
    private static Health health(ObservedWorkload workload) {
        if (workload.kind() == WorkloadKind.CRONJOB) {
            return Boolean.TRUE.equals(workload.suspend()) ? Health.DISABLED : Health.UNKNOWN;
        }
        Integer desired = workload.desiredReplicas();
        if (desired == null) {
            return Health.UNKNOWN;
        }
        if (desired == 0) {
            return Health.DISABLED;
        }
        // A missing `readyReplicas` is a zero, while a missing `desiredReplicas` above was an
        // abstention — and the asymmetry is the API's rather than ours. Kubernetes omits
        // `status.readyReplicas` when it is zero, so absent there genuinely means none are up;
        // `spec.replicas` is always populated on a Deployment or StatefulSet, so absent there means
        // we could not read the spec, and guessing would turn that into a deliberate shutdown.
        int ready = workload.readyReplicas() == null ? 0 : workload.readyReplicas();
        if (ready >= desired) {
            return Health.HEALTHY;
        }
        return ready == 0 ? Health.UNHEALTHY : Health.DEGRADED;
    }

    /**
     * ADR-0034: {@code "3 desired / 2 ready"}. ADR-0028 makes this a short line and a <em>gist</em>,
     * never an expected-output assertion — the core joins one of these per plugin into the composed
     * signal, so a node observed by three plugins reads
     * {@code "2/2 ready; lag 120; RUNNING, 3/3 tasks RUNNING"}.
     *
     * <p>ADR-0105: a node backed by several workloads names each one. With one workload the
     * reference would be noise repeating what {@code backings[]} already says; with two, an
     * unlabelled {@code "3 desired / 3 ready, 0 desired / 0 ready"} makes the reader guess which is
     * which. Ordered by reference, because the cluster's list order is not stable.
     */
    private static String rawSignal(List<ObservedWorkload> observed) {
        if (observed.isEmpty()) {
            return null;
        }
        boolean several = observed.size() > 1;
        return observed.stream()
                .map(workload -> several ? workload.reference() + " " + phrase(workload) : phrase(workload))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String phrase(ObservedWorkload workload) {
        if (workload.kind() == WorkloadKind.CRONJOB) {
            return Boolean.TRUE.equals(workload.suspend()) ? "suspended" : "scheduled";
        }
        Integer desired = workload.desiredReplicas();
        if (desired == null) {
            return "replicas unreported";
        }
        int ready = workload.readyReplicas() == null ? 0 : workload.readyReplicas();
        return desired == 0 ? "scaled to 0" : "%d desired / %d ready".formatted(desired, ready);
    }

    /**
     * ADR-0028's allow-list: {@code desiredReplicas} and {@code readyReplicas}, enumerated by name.
     *
     * <p>ADR-0105: summed across the node's workloads, because the overlay answers "how much of this
     * node is running" and a node backed by two workloads runs in proportion to both. A workload
     * with no {@code spec.replicas} contributes to neither sum — a zero there would render as a
     * scaled-down workload, which is the one reading it must not have.
     *
     * <p>The sums and the glyph can look like they disagree, and ADR-0105 accepts it: a node backed
     * by a StatefulSet scaled to zero and a Deployment at 1 desired / 0 ready reads {@code DISABLED}
     * beside {@code desiredReplicas: 1}. The glyph answers "does anyone need to act on this?" and
     * the overlay answers "how much is up?"; both are true, and reconciling them would mean
     * discarding one of them.
     */
    private static Map<String, Object> metrics(List<ObservedWorkload> observed) {
        // One filter, not two: a CronJob has no `spec.replicas` at all, so the null check already
        // excludes it. Naming the kind as well would be a second rule saying the same thing, and the
        // day they disagreed the wrong one would be the one someone had remembered to update.
        List<ObservedWorkload> replicated = observed.stream()
                .filter(workload -> workload.desiredReplicas() != null)
                .toList();
        if (replicated.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> metrics = new TreeMap<>();
        metrics.put(
                DESIRED_REPLICAS,
                replicated.stream().mapToInt(ObservedWorkload::desiredReplicas).sum());
        metrics.put(
                READY_REPLICAS,
                replicated.stream()
                        .mapToInt(workload -> workload.readyReplicas() == null ? 0 : workload.readyReplicas())
                        .sum());
        return metrics;
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
