// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.DiscoveredNode;
import io.nodqora.plugin.api.DiscoveryRequest;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.Link;
import io.nodqora.plugin.api.OutcomeStatus;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
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
        // The kind is folded because a human writes `StatefulSet`; the name is not, so a wrong-case
        // entry leaves an extra node on the graph rather than removing a real one.
        assertThat(keys(discover(objects, config(List.of("Deployment/kafka-connect")))))
                .containsExactly("payments-api");
        assertThat(keys(discover(objects, config(List.of("deployment/Kafka-Connect")))))
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

        // ADR-0031 names the value `"true"`. Anything else leaves the node visible, because
        // suppressing on a value we did not recognise fails toward a missing node.
        NamespaceObjects shouting = new NamespaceObjects(
                List.of(workload("migrations", Map.of(TopologyAnnotations.IGNORE, "TRUE"))), List.of(), List.of());
        assertThat(keys(discover(shouting, config(List.of())))).containsExactly("migrations");
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
                Map.of(),
                null,
                null,
                null);
        ObservedWorkload current = new ObservedWorkload(
                WorkloadKind.DEPLOYMENT,
                "payments-prod",
                "enricher-v2",
                Instant.parse("2026-06-01T00:00:00Z"),
                Map.of(),
                Map.of(TopologyAnnotations.NODE, "payments-enricher", TopologyAnnotations.TYPE, "service"),
                Map.of(),
                null,
                null,
                null);

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
    void a_losing_claimants_annotation_link_is_not_dropped() {
        ObservedWorkload one = new ObservedWorkload(
                WorkloadKind.DEPLOYMENT,
                "payments-prod",
                "enricher-v1",
                Instant.parse("2026-01-01T00:00:00Z"),
                Map.of(),
                Map.of(TopologyAnnotations.NODE, "payments-enricher", TopologyAnnotations.REPOSITORY, "acme/old"),
                Map.of(),
                null,
                null,
                null);
        ObservedWorkload two = new ObservedWorkload(
                WorkloadKind.DEPLOYMENT,
                "payments-prod",
                "enricher-v2",
                Instant.parse("2026-06-01T00:00:00Z"),
                Map.of(),
                Map.of(TopologyAnnotations.NODE, "payments-enricher", TopologyAnnotations.REPOSITORY, "acme/new"),
                Map.of(),
                null,
                null,
                null);

        // ADR-0021 takes *scalars* from the newest object; `links[]` is a collection, and ADR-0044
        // makes collections additive with an element identity. Two writers with one `rel` and
        // different URLs render two links, both visible — the same failure direction ADR-0031
        // chose, and it lets a human see that the two objects disagree.
        assertThat(links(discover(new NamespaceObjects(List.of(one, two), List.of(), List.of()), config(List.of()))
                        .nodes()
                        .getFirst()))
                .containsExactly("repository https://acme/new", "repository https://acme/old");
    }

    @Test
    void two_claimants_saying_the_same_thing_are_one_link() {
        ObservedWorkload one = annotated("enricher-v1", Instant.parse("2026-01-01T00:00:00Z"));
        ObservedWorkload two = annotated("enricher-v2", Instant.parse("2026-06-01T00:00:00Z"));

        assertThat(links(discover(
                                new NamespaceObjects(List.of(one, two), List.of(), List.of()),
                                config(List.of(), new KubernetesConfig.Links(
                                        "https://k8s.acme.io/{namespace}", null, null, null, null)))
                        .nodes()
                        .getFirst()))
                .containsExactly("workload https://k8s.acme.io/payments-prod");
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
    void an_annotation_naming_any_scheme_but_http_or_https_is_no_link() {
        // An annotation is written by whoever may edit a Deployment, which is a lower bar than
        // operating Nodqora, and its URL ends up as an `href` in somebody else's browser. A
        // `javascript:` URL there is script in the viewer's session, so the only schemes that make a
        // link are the two a browser navigates to. `javascript://%0a…` is the spelling that matters:
        // it carries `://`, which is all the old check asked for.
        DiscoveredNode node = node(
                discover(
                        objects(workload("payments-api", Map.of(
                                TopologyAnnotations.NODE, "payments-api",
                                TopologyAnnotations.REPOSITORY, "javascript://%0aalert(document.domain)",
                                TopologyAnnotations.RUNBOOK, "VBScript://alert(1)",
                                TopologyAnnotations.DOCS, "HTTPS://docs.acme.io/payments-api"))),
                        config(List.of())),
                "payments-api");

        assertThat(links(node)).containsExactly("docs HTTPS://docs.acme.io/payments-api");
    }

    @Test
    void a_template_composing_any_scheme_but_http_or_https_is_no_link() {
        // `{value}` is the annotation's half of the URL. A template that opens with it hands the
        // scheme to the annotation author too, so the composed URL is held to the same rule.
        DiscoveredNode node = node(
                discover(
                        objects(workload("payments-api", Map.of(
                                TopologyAnnotations.NODE, "payments-api",
                                TopologyAnnotations.GRAFANA, "javascript:alert(1)//"))),
                        config(List.of(), new KubernetesConfig.Links(null, null, null, "{value}/d", null))),
                "payments-api");

        assertThat(links(node)).isEmpty();
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

    // ---------------------------------------------------------------- prometheus

    private static final Map<String, String> BOTH_RECIPES = Map.of(
            "kafka-streams", "namespace={namespace},app={name}",
            "micrometer-http", "namespace={namespace},app={name}");

    @Test
    void no_recipe_map_means_no_prometheus_backing() {
        DiscoveredNode node = discover(objects(workload("payments-api", Map.of())), config(List.of()))
                .nodes()
                .getFirst();

        // ADR-0164: the map is the whole of the opt-in, exactly as no template means no link.
        assertThat(node.backings()).noneMatch(backing -> backing.plugin().equals("prometheus"));
    }

    @Test
    void every_workload_is_stamped_once_per_configured_recipe_with_its_selector_sorted() {
        DiscoveredNode node = discover(objects(workload("payments-api", Map.of())), prometheus(BOTH_RECIPES))
                .nodes()
                .getFirst();

        // ADR-0167: the recipe is the kind and the selector is the reference. Pairs are stored
        // sorted by label name, so two routes writing the same pairs in another order union.
        assertThat(node.backings())
                .filteredOn(backing -> backing.plugin().equals("prometheus"))
                .containsExactlyInAnyOrder(
                        new Backing("prometheus", "kafka-streams", "app=payments-api,namespace=payments-prod"),
                        new Backing("prometheus", "micrometer-http", "app=payments-api,namespace=payments-prod"));
    }

    @Test
    void a_recipe_left_out_of_the_map_is_never_stamped_and_kind_interpolates_too() {
        DiscoveredNode node = discover(
                        objects(workload("payments-api", Map.of())),
                        prometheus(Map.of("micrometer-http", " workload = {kind}/{name} ")))
                .nodes()
                .getFirst();

        assertThat(node.backings())
                .filteredOn(backing -> backing.plugin().equals("prometheus"))
                .containsExactly(new Backing("prometheus", "micrometer-http", "workload=deployment/payments-api"));
    }

    @Test
    void the_annotation_replaces_the_template_for_its_own_workload_only() {
        DiscoveryResult result = discover(
                objects(
                        workload(
                                "payments-api",
                                Map.of(TopologyAnnotations.PROMETHEUS,
                                        "micrometer-http: app={name}-http , namespace={namespace} ; kafka-streams:job=api")),
                        workload("payments-enricher", Map.of())),
                prometheus(BOTH_RECIPES));

        // ADR-0164's one exception to the union rule: the template is a guess about a single fact
        // and the annotation is that guess corrected, so the guess is not kept beside it.
        assertThat(node(result, "payments-api").backings())
                .filteredOn(backing -> backing.plugin().equals("prometheus"))
                .containsExactly(
                        new Backing("prometheus", "micrometer-http", "app=payments-api-http,namespace=payments-prod"),
                        new Backing("prometheus", "kafka-streams", "job=api"));
        assertThat(node(result, "payments-enricher").backings())
                .filteredOn(backing -> backing.plugin().equals("prometheus"))
                .hasSize(2);
    }

    @Test
    void the_annotation_stamps_without_any_template() {
        DiscoveredNode node = discover(
                        objects(workload("payments-api", Map.of(TopologyAnnotations.PROMETHEUS, "kafka-streams:app={name}"))),
                        config(List.of()))
                .nodes()
                .getFirst();

        assertThat(node.backings())
                .contains(new Backing("prometheus", "kafka-streams", "app=payments-api"));
    }

    @Test
    void a_binding_that_cannot_be_read_is_dropped_whole_and_its_siblings_survive() {
        String bindings = String.join(";",
                "kafka-streams:app={cluster}",          // unresolved placeholder
                "kafka-streams:app=a=b",                // `=` inside a value
                "kafka-streams:app=",                   // empty value would match series lacking the label
                "kafka-streams:",                       // empty selector is never every series
                "kafka-streams:1app=x",                 // not a Prometheus label name
                "kafka-streams:app=x,app=y",            // one label twice can match nothing
                ":app=x",                               // no recipe
                "no-colon-at-all",
                "",
                "micrometer-http:app={name}");
        DiscoveredNode node = discover(
                        objects(workload("payments-api", Map.of(TopologyAnnotations.PROMETHEUS, bindings))),
                        config(List.of()))
                .nodes()
                .getFirst();

        // ADR-0167: never partly applied, and failing toward no series rather than toward a matcher
        // that matches everything.
        assertThat(node.backings())
                .filteredOn(backing -> backing.plugin().equals("prometheus"))
                .containsExactly(new Backing("prometheus", "micrometer-http", "app=payments-api"));
    }

    @Test
    void a_template_that_cannot_be_read_stamps_nothing_for_that_recipe() {
        DiscoveredNode node = discover(
                        objects(workload("payments-api", Map.of())),
                        prometheus(Map.of(
                                "kafka-streams", "cluster={cluster},app={name}",
                                "micrometer-http", "app={name}")))
                .nodes()
                .getFirst();

        assertThat(node.backings())
                .filteredOn(backing -> backing.plugin().equals("prometheus"))
                .containsExactly(new Backing("prometheus", "micrometer-http", "app=payments-api"));
    }

    @Test
    void an_annotation_whose_every_binding_is_dropped_still_replaces_the_template() {
        DiscoveredNode node = discover(
                        objects(workload("payments-api", Map.of(TopologyAnnotations.PROMETHEUS, "kafka-streams:app="))),
                        prometheus(BOTH_RECIPES))
                .nodes()
                .getFirst();

        // The annotation is a correction, and a malformed correction is not permission to fall back
        // to the guess it corrected: that would read series someone has already said are wrong.
        assertThat(node.backings()).noneMatch(backing -> backing.plugin().equals("prometheus"));
    }

    @Test
    void the_prometheus_annotation_is_the_vocabularys_tenth_key() {
        assertThat(TopologyAnnotations.CLOSED).hasSize(10).contains("topology.io/prometheus");
        assertThat(TopologyAnnotations.unknownKeys(workload("payments-api", Map.of(TopologyAnnotations.PROMETHEUS, "x:y=z"))))
                .isEmpty();
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
                                List.of("payments-prod", "gone"), null, null, ignoringConnect(), null, null)));

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
                        "production", new KubernetesConfig(List.of("gone"), null, null, List.of(), null, null)));

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

    // ---------------------------------------------------------------- why a namespace could not be listed

    @Test
    void an_unresolvable_api_server_is_named_after_the_operation() {
        // The chain fabric8 6.13 really builds when there is no kubeconfig outside a cluster: its own
        // operation line, then an IOException repeating the root, then the root.
        RuntimeException failure = listingFailed(new IOException(
                "kubernetes.default.svc: nodename nor servname provided, or not known",
                new UnknownHostException("kubernetes.default.svc: nodename nor servname provided, or not known")));

        assertThat(reason(failure))
                .isEqualTo("namespace payments-prod could not be listed: listing deployments failed: "
                        + "UnknownHostException: kubernetes.default.svc: nodename nor servname provided, or not known");
    }

    @Test
    void a_refused_connection_keeps_both_the_address_and_the_refusal() {
        RuntimeException failure = listingFailed(new IOException(
                "Failed to connect to /127.0.0.1:6443",
                new ConnectException("Failed to connect to /127.0.0.1:6443")
                        .initCause(new ConnectException("Connection refused"))));

        assertThat(reason(failure))
                .endsWith("listing deployments failed: ConnectException: Failed to connect to /127.0.0.1:6443: "
                        + "Connection refused");
    }

    @Test
    void a_tls_failure_says_what_the_handshake_rejected() {
        RuntimeException failure = listingFailed(new IOException(
                "wrapped", new SSLHandshakeException("PKIX path building failed: unable to find valid certification path")));

        assertThat(reason(failure))
                .endsWith("listing deployments failed: SSLHandshakeException: PKIX path building failed: "
                        + "unable to find valid certification path");
    }

    @Test
    void an_http_status_the_api_server_returned_is_said_outright() {
        Status forbidden = new StatusBuilder()
                .withCode(403)
                .withReason("Forbidden")
                .withMessage("deployments.apps is forbidden: User \"system:serviceaccount:default:nodqora\" "
                        + "cannot list resource \"deployments\" in API group \"apps\" in the namespace \"payments-prod\"")
                .build();
        // fabric8 repeats this exception as its own cause; the reason stops at the first status it meets.
        KubernetesClientException failure = new KubernetesClientException(
                "Failure executing: GET at: https://10.0.0.1:6443/apis/apps/v1/namespaces/payments-prod/deployments.",
                403, forbidden, "apps", "v1", "deployments", "payments-prod");

        assertThat(reason(failure))
                .isEqualTo("namespace payments-prod could not be listed: listing deployments failed: HTTP 403 Forbidden: "
                        + "deployments.apps is forbidden: User \"system:serviceaccount:default:nodqora\" "
                        + "cannot list resource \"deployments\" in API group \"apps\" in the namespace \"payments-prod\"");
    }

    @Test
    void a_credential_the_api_server_refused_keeps_its_explanation() {
        Status unauthorized = new StatusBuilder()
                .withCode(401)
                .withReason("Unauthorized")
                .withMessage("the server has asked for the client to provide credentials")
                .build();

        assertThat(reason(new KubernetesClientException(
                        "Failure executing: GET at: https://10.0.0.1:6443/apis/apps/v1/namespaces/payments-prod/deployments.",
                        401, unauthorized, "apps", "v1", "deployments", "payments-prod")))
                .endsWith("listing deployments failed: HTTP 401 Unauthorized: "
                        + "the server has asked for the client to provide credentials");
    }

    @Test
    void a_cause_that_could_quote_the_kubeconfig_is_named_and_never_quoted() {
        // SnakeYAML's parse error, verbatim in shape: it prints the offending line, and in a kubeconfig
        // that line can be the token. The reason is served by the API and shown in the UI.
        RuntimeException failure = new KubernetesApiException(
                "the kubeconfig could not be read",
                new IllegalStateException("while parsing a flow sequence\n in reader, line 15, column 12:\n"
                        + "        token: [eyJhbGciOiJSUzI1NiIsImtpZCI6IlNFQ1JFVCJ9\n               ^"));

        assertThat(reason(failure))
                .isEqualTo("namespace payments-prod could not be listed: the kubeconfig could not be read: "
                        + "IllegalStateException")
                .doesNotContain("eyJhbGci");
    }

    @Test
    void the_recorded_fixture_is_complete() {
        assertThat(recorded("payments-prod").outcome().status()).isEqualTo(OutcomeStatus.COMPLETE);
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> ignoringConnect() {
        return List.of("statefulset/kafka-connect");
    }

    /** fabric8's own failure for a list call: the operation, carrying the resource it was listing. */
    private static KubernetesClientException listingFailed(Throwable cause) {
        return new KubernetesClientException(
                "Operation: [list]  for kind: [Deployment]  with name: [null]  in namespace: [payments-prod]  failed.",
                cause, "apps", "v1", "deployments", "payments-prod");
    }

    private static String reason(RuntimeException failure) {
        KubernetesApi api = (ignored, namespace) -> {
            throw failure;
        };
        DiscoveryResult result = new KubernetesPlugin(api).discover(new DiscoveryRequest<>("production", config(List.of())));
        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.FAILED);
        return result.outcome().reasons().getFirst();
    }

    private static DiscoveryResult recorded(String namespace) {
        return discover(recording(namespace), new KubernetesConfig(
                List.of(namespace), null, null, ignoringConnect(), null, null));
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
        return new KubernetesConfig(List.of("payments-prod"), null, null, ignore, links, null);
    }

    private static KubernetesConfig prometheus(Map<String, String> recipes) {
        return new KubernetesConfig(List.of("payments-prod"), null, null, List.of(), null, recipes);
    }

    private static NamespaceObjects objects(ObservedWorkload... workloads) {
        return new NamespaceObjects(List.of(workloads), List.of(), List.of());
    }

    private static ObservedWorkload workload(String name, Map<String, String> annotations) {
        return new ObservedWorkload(
                WorkloadKind.DEPLOYMENT,
                "payments-prod",
                name,
                Instant.parse("2026-01-01T00:00:00Z"),
                Map.of(),
                annotations,
                Map.of("app", name),
                null,
                null,
                null);
    }

    private static ObservedWorkload labelled(String name, Map<String, String> podLabels) {
        return new ObservedWorkload(
                WorkloadKind.DEPLOYMENT,
                "payments-prod",
                name,
                Instant.parse("2026-01-01T00:00:00Z"),
                Map.of(),
                Map.of(),
                podLabels,
                null,
                null,
                null);
    }

    private static ObservedWorkload annotated(String name, Instant created) {
        return new ObservedWorkload(
                WorkloadKind.DEPLOYMENT,
                "payments-prod",
                name,
                created,
                Map.of(),
                Map.of(TopologyAnnotations.NODE, "payments-enricher"),
                Map.of(),
                null,
                null,
                null);
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
