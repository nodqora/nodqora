package io.nodqora.plugin.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.Health;
import io.nodqora.plugin.api.HealthCapability.HealthRequest;
import io.nodqora.plugin.api.HealthCapability.HealthResult;
import io.nodqora.plugin.api.HealthCapability.ObservableNode;
import io.nodqora.plugin.api.OutcomeStatus;
import io.nodqora.plugin.api.StateContribution;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Kubernetes contribution, against the same outbound seam discovery is tested at (ADR-0099).
 *
 * <p>Two rules carry most of the weight here and both are places the obvious implementation is
 * wrong: readiness is <b>arithmetic</b> rather than {@code status.conditions} (ADR-0025), and
 * {@code DISABLED} comes from <b>declared intent</b> rather than from observed absence (ADR-0029,
 * ADR-0034).
 */
class KubernetesHealthTest {

    private static final String NAMESPACE = "payments-prod";

    // ---------------------------------------------------------------- readiness arithmetic

    @Test
    void all_some_and_none_ready_are_healthy_degraded_and_unhealthy() {
        assertThat(healthOf(deployment("api", 3, 3))).isEqualTo(Health.HEALTHY);
        assertThat(healthOf(deployment("api", 3, 2))).isEqualTo(Health.DEGRADED);
        assertThat(healthOf(deployment("api", 3, 0))).isEqualTo(Health.UNHEALTHY);
    }

    @Test
    void two_of_three_is_degraded_and_not_the_healthy_that_status_conditions_would_give() {
        // ADR-0025's whole argument in one assertion. `Available=True` holds at 2 of 3 replicas, so
        // reading conditions would render the fixture's `enricher-v2` HEALTHY and erase the one
        // Kubernetes signal the reference pipeline exists to exercise. The deploy flap this causes
        // is honest and self-clearing, and `rawSignal` carries the counts so nobody has to guess.
        StateContribution contribution = observe(deployment("enricher-v2", 3, 2));

        assertThat(contribution.health()).isEqualTo(Health.DEGRADED);
        assertThat(contribution.rawSignal()).isEqualTo("3 desired / 2 ready");
        assertThat(contribution.metrics()).isEqualTo(Map.of("desiredReplicas", 3, "readyReplicas", 2));
    }

    @Test
    void more_ready_than_desired_is_healthy_rather_than_arithmetic_nonsense() {
        // A scale-down in progress genuinely reports this for a moment. "All desired are ready" is
        // true, and there is nothing to report.
        assertThat(healthOf(deployment("api", 2, 3))).isEqualTo(Health.HEALTHY);
    }

    // ---------------------------------------------------------------- declared intent

    @Test
    void scaled_to_zero_is_disabled_because_the_spec_records_the_decision() {
        // ADR-0029 requires evidence of *intent* before anything may be called DISABLED, and this is
        // the case it left open for Kubernetes: `spec.replicas: 0` is declarative and lives in the
        // object's own spec. It is why `kafka` may never emit DISABLED — an EMPTY consumer group is
        // the exact state of a scaled-down consumer and a crashed one — and why this plugin may.
        StateContribution contribution = observe(deployment("enricher-v2", 0, 0));

        assertThat(contribution.health()).isEqualTo(Health.DISABLED);
        assertThat(contribution.rawSignal()).isEqualTo("scaled to 0");
    }

    @Test
    void zero_desired_is_checked_before_the_arithmetic_that_has_no_answer_there() {
        // Nought of nought ready is both "all of them" and "none of them", so the all/some/none rule
        // has no honest reading — ADR-0025 applies it only when desired > 0. Ordering the checks the
        // other way round would silently report a deliberate shutdown as HEALTHY.
        assertThat(healthOf(deployment("api", 0, 0))).isEqualTo(Health.DISABLED);
    }

    @Test
    void an_autoscaler_scaling_to_zero_is_indistinguishable_and_that_is_correct() {
        // ADR-0034: an HPA or KEDA scaling a workload to zero reads DISABLED. There is no way to
        // tell it from a human's `kubectl scale`, and no reason to want one — it is still a recorded
        // decision that the workload should not be running now.
        assertThat(healthOf(deployment("api", 0, 0))).isEqualTo(Health.DISABLED);
    }

    @Test
    void a_suspended_cronjob_is_disabled_and_a_running_one_abstains() {
        // ADR-0034: `spec.suspend: true` is the CronJob's declared intent. A running CronJob has no
        // replica concept and ADR-0030 does not discover its Jobs, so there is genuinely nothing to
        // report — and reporting HEALTHY would be a claim nobody checked.
        assertThat(healthOf(cronJob("nightly-reconcile", true))).isEqualTo(Health.DISABLED);
        assertThat(observeOrNothing(cronJob("nightly-reconcile", false))).isNull();
    }

    @Test
    void a_cronjob_contributes_no_replica_metrics_because_it_has_none() {
        // A zero here would render on the canvas as a scaled-down workload, which is the one reading
        // it must not have.
        assertThat(observe(cronJob("nightly-reconcile", true)).metrics()).isEmpty();
    }

    // ---------------------------------------------------------------- what does not contribute

    @Test
    void a_service_and_an_ingress_are_inert_for_health() {
        // ADR-0034: only workload backings contribute. `payments-api` carries three `kubernetes`
        // backings and exactly one has a readiness concept, so the counts are the Deployment's and
        // not three times the Deployment's.
        ObservableNode node = new ObservableNode(
                "payments-api",
                List.of(
                        new Backing("kubernetes", "deployment", NAMESPACE + "/payments-api"),
                        new Backing("kubernetes", "service", NAMESPACE + "/payments-api"),
                        new Backing("kubernetes", "ingress", NAMESPACE + "/payments-api")));

        StateContribution contribution =
                observe(List.of(node), List.of(deployment("payments-api", 3, 3))).get("payments-api");

        assertThat(contribution.health()).isEqualTo(Health.HEALTHY);
        assertThat(contribution.metrics()).isEqualTo(Map.of("desiredReplicas", 3, "readyReplicas", 3));
    }

    @Test
    void a_foreign_domain_backing_is_not_this_plugins_to_read() {
        // ADR-0022: `Backing.plugin` names the technology domain, not the discoverer. `kubernetes`
        // put the consumer group on the node and still may not observe it — the plugin that can read
        // that lag is not the one that can say whose lag it is.
        ObservableNode node = new ObservableNode(
                "payments-enricher", List.of(new Backing("kafka", "consumer-group", "enrich-consumer-prod")));

        assertThat(observe(List.of(node), List.of()).get("payments-enricher")).isNull();
    }

    @Test
    void a_backing_whose_object_has_vanished_abstains_rather_than_alarming() {
        // ADR-0026: discovery runs at five minutes and this at thirty seconds, so a deliberately
        // deleted Deployment would otherwise paint ten cycles of red for something a human removed.
        // Deletion is discovery's to read from its own snapshot, never encoded as health.
        ObservableNode node = new ObservableNode(
                "payments-api", List.of(new Backing("kubernetes", "deployment", NAMESPACE + "/gone")));

        assertThat(observe(List.of(node), List.of()).get("payments-api")).isNull();
    }

    @Test
    void an_unreported_replica_count_abstains_rather_than_reading_as_a_shutdown() {
        // The nullable fields earn their nullability here: defaulting a missing `spec.replicas` to
        // zero would turn every object we could not fully read into a deliberate shutdown, which is
        // the one direction this must not fail in.
        assertThat(observeOrNothing(deployment("api", null, null))).isNull();
    }

    // ---------------------------------------------------------------- the collapse, inside the plugin

    @Test
    void several_workloads_on_one_node_collapse_with_the_same_rule_the_engine_uses() {
        // ADR-0034: one collapse algorithm, applied at two levels, and no second rule to keep in
        // step. This is also the mechanism behind ADR-0034's warning — scaling a shared StatefulSet
        // to zero contributes DISABLED to every node it backs, and DISABLED wins outright at both
        // levels, so both connectors read DISABLED even though nobody paused them. Deliberate: they
        // genuinely are not running, and UNHEALTHY would page an on-call for an intentional
        // scale-down.
        ObservableNode node = new ObservableNode(
                "payments-es-sink",
                List.of(
                        new Backing("kubernetes", "statefulset", NAMESPACE + "/kafka-connect"),
                        new Backing("kubernetes", "deployment", NAMESPACE + "/sidecar")));

        StateContribution contribution = observe(
                        List.of(node), List.of(statefulSet("kafka-connect", 0, 0), deployment("sidecar", 1, 0)))
                .get("payments-es-sink");

        assertThat(contribution.health()).isEqualTo(Health.DISABLED);
        // ADR-0105: each workload is named when there is more than one, because an unlabelled
        // "scaled to 0, 1 desired / 0 ready" makes the reader guess which is which.
        assertThat(contribution.rawSignal())
                .isEqualTo("payments-prod/kafka-connect scaled to 0, payments-prod/sidecar 1 desired / 0 ready");
        // Summed, and this is ADR-0105's accepted consequence in one assertion: the card reads
        // DISABLED beside `desiredReplicas: 1`. The glyph answers "does anyone need to act on
        // this?" and the overlay answers "how much is up?" — both true, and reconciling them would
        // mean throwing one of them away.
        assertThat(contribution.metrics()).isEqualTo(Map.of("desiredReplicas", 1, "readyReplicas", 0));
    }

    // ---------------------------------------------------------------- outcome

    @Test
    void every_namespace_unreachable_is_failed_and_some_is_partial() {
        KubernetesConfig config =
                new KubernetesConfig(List.of(NAMESPACE, "second-namespace"), null, null, List.of(), null);
        ObservableNode node = new ObservableNode(
                "api", List.of(new Backing("kubernetes", "deployment", NAMESPACE + "/api")));

        // ADR-0026: `health` says what we found, `outcome` says how well we looked. A FAILED run
        // leaves the store untouched (ADR-0046), so the last good readings stand and go visibly
        // stale rather than every node flipping grey because one poll blinked.
        assertThat(observe(node, config, namespace -> {
                    throw new KubernetesApiException("unreachable");
                })
                .outcome()
                .status())
                .isEqualTo(OutcomeStatus.FAILED);

        HealthResult partial = observe(node, config, namespace -> {
            if (namespace.equals("second-namespace")) {
                throw new KubernetesApiException("unreachable");
            }
            return new NamespaceObjects(List.of(deployment("api", 1, 1)), List.of(), List.of());
        });
        assertThat(partial.outcome().status()).isEqualTo(OutcomeStatus.PARTIAL);
        // A PARTIAL still carries what it did see: the reachable namespace's node keeps its verdict.
        assertThat(partial.contributions().get("api").health()).isEqualTo(Health.HEALTHY);
    }

    @Test
    void nothing_routed_here_costs_no_cluster_round_trip() {
        // Every node in the environment belongs to some other plugin's domain. There is nothing this
        // plugin could look at, and COMPLETE with no contributions is both the truthful answer and
        // the one that clears anything stale.
        HealthResult result = new KubernetesPlugin(unreachable())
                .observe(new HealthRequest<>("production", List.of(), config()));

        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.COMPLETE);
        assertThat(result.contributions()).isEmpty();
    }

    @Test
    void the_recorded_fixture_reproduces_section_eights_kubernetes_column() {
        Map<String, StateContribution> contributions = observe(
                List.of(
                        new ObservableNode(
                                "payments-api",
                                List.of(new Backing("kubernetes", "deployment", NAMESPACE + "/payments-api"))),
                        new ObservableNode(
                                "payments-enricher",
                                List.of(new Backing("kubernetes", "deployment", NAMESPACE + "/enricher-v2")))),
                new RecordedKubernetesApi());

        assertThat(contributions.get("payments-api").health()).isEqualTo(Health.HEALTHY);
        assertThat(contributions.get("payments-enricher").health()).isEqualTo(Health.DEGRADED);
        assertThat(contributions.get("payments-enricher").rawSignal()).isEqualTo("3 desired / 2 ready");
    }

    // ---------------------------------------------------------------- helpers

    private static KubernetesConfig config() {
        return new KubernetesConfig(List.of(NAMESPACE), null, null, List.of(), null);
    }

    private static KubernetesApi unreachable() {
        return (ignored, namespace) -> {
            throw new AssertionError("the cluster must not be reached when nothing is routed here");
        };
    }

    private static ObservedWorkload deployment(String name, Integer desired, Integer ready) {
        return workload(WorkloadKind.DEPLOYMENT, name, desired, ready, null);
    }

    private static ObservedWorkload statefulSet(String name, Integer desired, Integer ready) {
        return workload(WorkloadKind.STATEFULSET, name, desired, ready, null);
    }

    private static ObservedWorkload cronJob(String name, boolean suspended) {
        return workload(WorkloadKind.CRONJOB, name, null, null, suspended);
    }

    private static ObservedWorkload workload(
            WorkloadKind kind, String name, Integer desired, Integer ready, Boolean suspend) {
        return new ObservedWorkload(
                kind,
                NAMESPACE,
                name,
                Instant.parse("2026-01-01T00:00:00Z"),
                Map.of(),
                Map.of(),
                Map.of(),
                desired,
                ready,
                suspend);
    }

    /** The node routed to a single workload of the same name — the ordinary shape. */
    private static ObservableNode routedTo(ObservedWorkload workload) {
        return new ObservableNode(
                workload.name(),
                List.of(new Backing("kubernetes", workload.kind().lowercased(), workload.reference())));
    }

    private static StateContribution observe(ObservedWorkload workload) {
        StateContribution contribution = observeOrNothing(workload);
        assertThat(contribution).as("expected a contribution for %s", workload.name()).isNotNull();
        return contribution;
    }

    private static StateContribution observeOrNothing(ObservedWorkload workload) {
        return observe(List.of(routedTo(workload)), List.of(workload)).get(workload.name());
    }

    private static Health healthOf(ObservedWorkload workload) {
        return observe(workload).health();
    }

    private static Map<String, StateContribution> observe(
            List<ObservableNode> nodes, List<ObservedWorkload> workloads) {
        return observe(nodes, (ignored, namespace) -> new NamespaceObjects(workloads, List.of(), List.of()));
    }

    private static Map<String, StateContribution> observe(List<ObservableNode> nodes, KubernetesApi api) {
        return new KubernetesPlugin(api)
                .observe(new HealthRequest<>("production", nodes, config()))
                .contributions();
    }

    private interface Namespaces {
        NamespaceObjects list(String namespace);
    }

    private static HealthResult observe(ObservableNode node, KubernetesConfig config, Namespaces namespaces) {
        KubernetesApi api = (ignored, namespace) -> namespaces.list(namespace);
        return new KubernetesPlugin(api).observe(new HealthRequest<>("production", List.of(node), config));
    }
}
