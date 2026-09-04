package io.nodqora.plugin.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.DiscoveredNode;
import io.nodqora.plugin.api.DiscoveryRequest;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.Link;
import io.nodqora.plugin.api.OutcomeStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The plugin against its own outbound seam (ADR-0099). Everything above the seam has behaviour worth
 * testing; the fabric8 client below it has none that a test could reach without a cluster.
 */
class KubernetesDiscoveryTest {

    private static final KubernetesConfig.Links PRODUCTION_LINKS = new KubernetesConfig.Links(
            "https://k8s.acme.io/{namespace}/{kind}/{name}",
            "https://k8s.acme.io/{namespace}/{kind}/{name}/pods",
            "https://grafana.acme.io/explore?ns={namespace}&app={name}",
            "https://grafana.acme.io/d/{value}",
            "https://argocd.acme.io/applications/{value}");

    // ---------------------------------------------------------------- the fixture

    @Test
    void the_fixtures_three_way_mismatch_resolves_to_one_node() {
        DiscoveredNode enricher = node(recorded("payments-prod"), "payments-enricher");

        // ADR-0021: Deployment `enricher-v2`, node key `payments-enricher`, consumer group
        // `enrich-consumer-prod` — three strings, one logical service. Resolution comes from the
        // annotation, and string equality would have produced two nodes and no error.
        assertThat(enricher.backings())
                .contains(
                        new Backing("kubernetes", "deployment", "payments-prod/enricher-v2"),
                        new Backing("kafka", "consumer-group", "enrich-consumer-prod"));
        assertThat(enricher.type()).isEqualTo("service");
        assertThat(enricher.ownerKey()).isEqualTo("payments-platform");
    }

    @Test
    void payments_api_is_backed_by_its_deployment_service_and_ingress() {
        // ADR-0030: Ingress -> Service -> workload, by selector. `payments-api` matches its
        // Deployment by name — ADR-0021's tier 2, and the happy path the enricher is not.
        assertThat(node(recorded("payments-prod"), "payments-api").backings())
                .containsExactlyInAnyOrder(
                        new Backing("kubernetes", "deployment", "payments-prod/payments-api"),
                        new Backing("kubernetes", "service", "payments-prod/payments-api"),
                        new Backing("kubernetes", "ingress", "payments-prod/payments-api"));
    }

    @Test
    void the_connect_workload_is_suppressed_so_the_inventory_holds() {
        DiscoveryResult production = recorded("payments-prod");

        // ADR-0031: `kafka-connect` hosts both connectors and is not itself a node. Suppression
        // removes node emission only — `connect` still stamps it as a backing on both connectors,
        // which is what keeps ADR-0013's health routing to them intact.
        assertThat(production.nodes()).extracting(DiscoveredNode::key)
                .containsExactly("payments-api", "payments-enricher");
    }

    @Test
    void staging_records_its_own_consumer_group() {
        assertThat(node(recorded("payments-staging"), "payments-enricher").backings())
                .contains(new Backing("kafka", "consumer-group", "enrich-consumer-staging"));
    }

    // ---------------------------------------------------------------- suppression

    @Test
    void the_deny_list_is_exact_and_never_a_pattern() {
        NamespaceObjects objects = new NamespaceObjects(
                List.of(workload("kafka-connect", Map.of()), workload("payments-api", Map.of())),
                List.of(),
                List.of());

        // ADR-0031: no globs, no regex. `kafka-*` quietly eating a real service is the missing-node
        // failure returning through the convenience door, and a missing node reads as drift.
        assertThat(keys(discover(objects, config(List.of("deployment/kafka-connect")))))
                .containsExactly("payments-api");
        assertThat(keys(discover(objects, config(List.of("deployment/kafka-")))))
                .containsExactly("kafka-connect", "payments-api");
    }

    @Test
    void the_ignore_annotation_is_the_other_route_and_either_suffices() {
        NamespaceObjects objects = new NamespaceObjects(
                List.of(workload("migrations", Map.of(TopologyAnnotations.IGNORE, "true"))), List.of(), List.of());

        // Neither route alone is sufficient: the annotation needs an edit in a repo owned by another
        // team, where an operator will revert it; the deny-list needs a PR against the nodqora config
        // repo for a service team's own migration runner.
        assertThat(discover(objects, config(List.of())).nodes()).isEmpty();
    }

    @Test
    void a_suppressed_workload_takes_no_service_backing_with_it() {
        NamespaceObjects objects = new NamespaceObjects(
                List.of(workload("kafka-connect", Map.of())),
                List.of(new ObservedService("payments-prod", "kafka-connect", Map.of("app", "kafka-connect"))),
                List.of());

        assertThat(discover(objects, config(List.of("deployment/kafka-connect"))).nodes())
                .isEmpty();
    }

    // ---------------------------------------------------------------- identity

    @Test
    void two_objects_claiming_one_key_become_one_node_scalared_by_the_newest() {
        ObservedWorkload old = new ObservedWorkload(
                WorkloadKind.DEPLOYMENT,
                "payments-prod",
                "enricher-v1",
                Instant.parse("2026-01-01T00:00:00Z"),
                Map.of(),
                Map.of(TopologyAnnotations.NODE, "payments-enricher", TopologyAnnotations.TYPE, "worker"),
                Map.of());
        ObservedWorkload current = new ObservedWorkload(
                WorkloadKind.DEPLOYMENT,
                "payments-prod",
                "enricher-v2",
                Instant.parse("2026-06-01T00:00:00Z"),
                Map.of(),
                Map.of(TopologyAnnotations.NODE, "payments-enricher", TopologyAnnotations.TYPE, "service"),
                Map.of());

        List<DiscoveredNode> nodes =
                discover(new NamespaceObjects(List.of(old, current), List.of(), List.of()), config(List.of()))
                        .nodes();

        assertThat(nodes).hasSize(1);
        // ADR-0021: scalars from the newest by creationTimestamp — stable across polls, and during a
        // progressive rollout it names the current workload. Processing one claimant and skipping the
        // rest would flip with the cluster's unstable list order and flap the node with no visible cause.
        assertThat(nodes.getFirst().type()).isEqualTo("service");
        // Backings are unioned, so ADR-0013's health routing still reaches every claimant.
        assertThat(nodes.getFirst().backings()).containsExactly(
                new Backing("kubernetes", "deployment", "payments-prod/enricher-v2"),
                new Backing("kubernetes", "deployment", "payments-prod/enricher-v1"));
        // The contest is recorded under this plugin's own namespace and logged — never through
        // `outcome`, which gates deletion (ADR-0046).
        assertThat(nodes.getFirst().metadata())
                .isEqualTo(Map.of("contestedBy", List.of("deployment/enricher-v2", "deployment/enricher-v1")));
    }

    @Test
    void a_contested_key_carries_a_workload_link_per_claimant() {
        ObservedWorkload one = annotated("enricher-v1", Instant.parse("2026-01-01T00:00:00Z"));
        ObservedWorkload two = annotated("enricher-v2", Instant.parse("2026-06-01T00:00:00Z"));

        // ADR-0044: `rel` could not be the link key, precisely because ADR-0032 admits one
        // workload/pods link per workload backing and ADR-0021 unions backings.
        assertThat(links(discover(
                                new NamespaceObjects(List.of(one, two), List.of(), List.of()),
                                config(List.of(), PRODUCTION_LINKS))
                        .nodes()
                        .getFirst()))
                .contains(
                        "workload https://k8s.acme.io/payments-prod/deployment/enricher-v2",
                        "workload https://k8s.acme.io/payments-prod/deployment/enricher-v1");
    }

    @Test
    void keys_are_contested_case_insensitively() {
        ObservedWorkload lower = workload("payments-api", Map.of());
        ObservedWorkload upper = workload("other", Map.of(TopologyAnnotations.NODE, "Payments-API"));

        // ADR-0020: uniqueness and merge lookup are on the case-folded form, so a `Payments-API`
        // typo cannot split the node silently.
        assertThat(discover(new NamespaceObjects(List.of(lower, upper), List.of(), List.of()), config(List.of()))
                        .nodes())
                .hasSize(1);
    }

    // ---------------------------------------------------------------- scalars and edges

    @Test
    void an_unannotated_workload_has_no_type_at_all() {
        DiscoveredNode node = discover(
                        new NamespaceObjects(List.of(workload("payments-api", Map.of())), List.of(), List.of()),
                        config(List.of()))
                .nodes()
                .getFirst();

        // ADR-0091: no kind-derived default, for any of the three kinds. A kind is a deployment
        // mechanism, not a component role — a StatefulSet is a database as often as a service. The
        // node renders on ADR-0001's fallback descriptor, and that is the priced cost.
        assertThat(node.type()).isNull();
        assertThat(node.ownerKey()).isNull();
        // ADR-0034: `displayName` is null on purpose — not to resolve a merge conflict with YAML,
        // but to avoid manufacturing one.
        assertThat(node.displayName()).isNull();
        assertThat(node.description()).isNull();
    }

    @Test
    void the_plugin_emits_no_edges_and_no_descriptors() {
        DiscoveryResult production = recorded("payments-prod");

        // ADR-0033: counting the fixture's edges against the plugins that can produce them settles
        // it before any inference source is evaluated — Kubernetes contributes zero. The interesting
        // casualty is `payments-enricher CONSUMES_FROM payments.events.raw.v1`: this plugin knows
        // node -> group and `kafka` knows group -> topic, and neither knows both halves.
        assertThat(production.edges()).isEmpty();
        assertThat(production.owners()).isEmpty();
        assertThat(production.descriptors()).isEmpty();
    }

    @Test
    void consumer_groups_are_comma_separated_because_nothing_says_a_workload_consumes_one_topic() {
        DiscoveredNode node = discover(
                        new NamespaceObjects(
                                List.of(workload(
                                        "enricher",
                                        Map.of(TopologyAnnotations.CONSUMER_GROUPS, "one, two ,three"))),
                                List.of(),
                                List.of()),
                        config(List.of()))
                .nodes()
                .getFirst();

        assertThat(node.backings())
                .contains(
                        new Backing("kafka", "consumer-group", "one"),
                        new Backing("kafka", "consumer-group", "two"),
                        new Backing("kafka", "consumer-group", "three"));
    }

    // ---------------------------------------------------------------- links

    @Test
    void no_template_means_no_link() {
        DiscoveredNode node = node(discover(recording("payments-prod"), config(ignoringConnect())), "payments-api");

        // ADR-0032: never a half-composed URL. `repository`, `runbook` and `docs` need no template —
        // their values are URLs — so they are all that survives an unconfigured plugin.
        assertThat(links(node))
                .containsExactly(
                        "repository https://github.com/acme/payments-api",
                        "runbook https://wiki/runbooks/payments-api",
                        "docs https://docs.acme.io/payments-api");
    }

    @Test
    void an_id_plus_a_per_environment_template_renders_correctly_in_both_environments() {
        String staging = "https://grafana-staging.acme.io/d/{value}";

        // This is the reason the vocabulary is shaped this way rather than a matter of taste: the
        // same manifest is deployed to both environments, so an absolute Grafana URL in the
        // annotation would point staging's node at production's dashboard.
        assertThat(links(node(discover(recording("payments-prod"), config(ignoringConnect(), PRODUCTION_LINKS)),
                        "payments-enricher")))
                .contains("dashboard https://grafana.acme.io/d/payments-enricher-overview");
        assertThat(links(node(
                        discover(
                                recording("payments-prod"),
                                config(
                                        ignoringConnect(),
                                        new KubernetesConfig.Links(null, null, null, staging, null))),
                        "payments-enricher")))
                .contains("dashboard https://grafana-staging.acme.io/d/payments-enricher-overview");
    }

    @Test
    void the_gitops_link_reads_argos_own_label_and_the_fixture_is_unevenly_annotated() {
        DiscoveryResult production = discover(recording("payments-prod"), config(ignoringConnect(), PRODUCTION_LINKS));

        // Argo manages `payments-api` and not the enricher. ADR-0032 explains that unevenness
        // honestly, at zero annotation cost, off a convention every Argo-managed object already has.
        assertThat(links(node(production, "payments-api")))
                .contains("gitops https://argocd.acme.io/applications/payments-api");
        assertThat(links(node(production, "payments-enricher")))
                .noneMatch(link -> link.startsWith("gitops "));
    }

    @Test
    void a_template_naming_a_placeholder_this_plugin_cannot_fill_composes_nothing() {
        DiscoveredNode node = node(
                discover(
                        recording("payments-prod"),
                        config(
                                ignoringConnect(),
                                new KubernetesConfig.Links(
                                        "https://k8s.acme.io/{cluster}/{namespace}/{name}",
                                        null, null, null, null))),
                "payments-api");

        assertThat(links(node)).noneMatch(link -> link.startsWith("workload "));
    }

    @Test
    void an_unrecognised_topology_key_is_ignored_and_becomes_nothing() {
        DiscoveredNode node = discover(
                        new NamespaceObjects(
                                List.of(workload(
                                        "payments-api",
                                        Map.of("topology.io/dashboard", "https://example.test/nope"))),
                                List.of(),
                                List.of()),
                        config(List.of(), PRODUCTION_LINKS))
                .nodes()
                .getFirst();

        // ADR-0032: the vocabulary is closed, so an unknown key is never turned into a link, into
        // metadata, or into anything else. The log line is the only defence and cannot be more.
        assertThat(links(node)).noneMatch(link -> link.contains("example.test"));
    }

    // ---------------------------------------------------------------- selectors

    @Test
    void a_service_selecting_nothing_attaches_to_nothing() {
        NamespaceObjects objects = new NamespaceObjects(
                List.of(workload("payments-api", Map.of())),
                List.of(
                        new ObservedService("payments-prod", "headless", Map.of()),
                        new ObservedService("payments-prod", "elsewhere", Map.of("app", "other"))),
                List.of());

        assertThat(discover(objects, config(List.of())).nodes().getFirst().backings())
                .containsExactly(new Backing("kubernetes", "deployment", "payments-prod/payments-api"));
    }

    @Test
    void a_service_selecting_two_workloads_backs_both_nodes() {
        NamespaceObjects objects = new NamespaceObjects(
                List.of(
                        labelled("blue", Map.of("app", "payments-api", "colour", "blue")),
                        labelled("green", Map.of("app", "payments-api", "colour", "green"))),
                List.of(new ObservedService("payments-prod", "payments-api", Map.of("app", "payments-api"))),
                List.of());

        // ADR-0030: one object backing many nodes was already legal (ADR-0005) and is used here on
        // purpose. A selector match is a backing attachment, never an edge.
        assertThat(discover(objects, config(List.of())).nodes())
                .allSatisfy(node -> assertThat(node.backings())
                        .contains(new Backing("kubernetes", "service", "payments-prod/payments-api")));
    }

    // ---------------------------------------------------------------- outcome

    @Test
    void an_unreachable_namespace_among_reachable_ones_is_partial() {
        DiscoveryResult result = new KubernetesPlugin(new RecordedKubernetesApi())
                .discover(new DiscoveryRequest<>(
                        "production",
                        new KubernetesConfig(
                                List.of("payments-prod", "gone"), null, null, ignoringConnect(), null)));

        // ADR-0026, ADR-0046: PARTIAL retains what it cannot see rather than deleting it, so one
        // blind namespace never empties the graph.
        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.PARTIAL);
        assertThat(result.outcome().reasons()).anyMatch(reason -> reason.contains("gone"));
        assertThat(keys(result)).containsExactly("payments-api", "payments-enricher");
    }

    @Test
    void every_namespace_unreachable_is_failed_and_changes_nothing_in_the_store() {
        DiscoveryResult result = new KubernetesPlugin(new RecordedKubernetesApi())
                .discover(new DiscoveryRequest<>(
                        "production", new KubernetesConfig(List.of("gone"), null, null, List.of(), null)));

        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.FAILED);
        assertThat(result.nodes()).isEmpty();
    }

    @Test
    void an_enumerated_namespace_that_produced_nothing_is_partial() {
        // ADR-0047: the zero-output guard is a plugin obligation, because the engine cannot tell an
        // empty scope from an empty result — and a COMPLETE empty snapshot deletes on sight.
        DiscoveryResult result =
                discover(new NamespaceObjects(List.of(), List.of(), List.of()), config(List.of()));

        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.PARTIAL);
        assertThat(result.outcome().reasons())
                .containsExactly("namespace payments-prod produced no node-producing workload");
    }

    @Test
    void the_recorded_fixture_is_complete() {
        assertThat(recorded("payments-prod").outcome().status()).isEqualTo(OutcomeStatus.COMPLETE);
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> ignoringConnect() {
        return List.of("statefulset/kafka-connect");
    }

    private static DiscoveryResult recorded(String namespace) {
        return discover(recording(namespace), new KubernetesConfig(
                List.of(namespace), null, null, ignoringConnect(), null));
    }

    private static NamespaceObjects recording(String namespace) {
        return new RecordedKubernetesApi().list(null, namespace);
    }

    private static DiscoveryResult discover(NamespaceObjects objects, KubernetesConfig config) {
        KubernetesApi api = (ignored, namespace) -> objects;
        return new KubernetesPlugin(api).discover(new DiscoveryRequest<>("production", config));
    }

    private static KubernetesConfig config(List<String> ignore) {
        return config(ignore, null);
    }

    private static KubernetesConfig config(List<String> ignore, KubernetesConfig.Links links) {
        return new KubernetesConfig(List.of("payments-prod"), null, null, ignore, links);
    }

    private static ObservedWorkload workload(String name, Map<String, String> annotations) {
        return new ObservedWorkload(
                WorkloadKind.DEPLOYMENT,
                "payments-prod",
                name,
                Instant.parse("2026-01-01T00:00:00Z"),
                Map.of(),
                annotations,
                Map.of("app", name));
    }

    private static ObservedWorkload labelled(String name, Map<String, String> podLabels) {
        return new ObservedWorkload(
                WorkloadKind.DEPLOYMENT,
                "payments-prod",
                name,
                Instant.parse("2026-01-01T00:00:00Z"),
                Map.of(),
                Map.of(),
                podLabels);
    }

    private static ObservedWorkload annotated(String name, Instant created) {
        return new ObservedWorkload(
                WorkloadKind.DEPLOYMENT,
                "payments-prod",
                name,
                created,
                Map.of(),
                Map.of(TopologyAnnotations.NODE, "payments-enricher"),
                Map.of());
    }

    private static List<String> keys(DiscoveryResult result) {
        return result.nodes().stream().map(DiscoveredNode::key).toList();
    }

    private static List<String> links(DiscoveredNode node) {
        return node.links().stream().map(link -> link.rel() + " " + link.url()).toList();
    }

    private static DiscoveredNode node(DiscoveryResult result, String key) {
        return result.nodes().stream()
                .filter(node -> node.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node keyed " + key));
    }
}
