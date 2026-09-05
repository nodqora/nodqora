package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The slice's done-when: the reference pipeline, read off the real endpoint, against ADR-0099's
 * golden documents.
 *
 * <p>These assert the wire rather than the service, because the golden documents describe what a
 * client receives — including that {@code null} crosses it unresolved (ADR-0058).
 */
class ReferencePipelineGraphTest extends NodqoraIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper mapper;

    private JsonNode graph(String environmentKey) {
        return Golden.normalize(
                http.getForObject("/api/environments/" + environmentKey + "/graph", JsonNode.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"production", "staging"})
    void the_graph_document_matches_its_golden(String environmentKey) {
        assertThat(graph(environmentKey))
                .as("§2 and §3 in full, over all four writers")
                .isEqualTo(Golden.read(mapper, "graph-" + environmentKey + ".json"));
    }

    @Test
    void production_is_ten_nodes_and_section_threes_nine_edges() {
        JsonNode graph = graph("production");

        // Ten nodes with four discovery plugins running, and the count is unchanged from the two-
        // plugin slice: `kubernetes` finds three workloads and ADR-0031 suppresses the one that is
        // not a node; `kafka` and `connect` mint five keys between them and every one merges onto a
        // key `yaml` already carries. An eleventh node would mean either the `kafka-connect`
        // StatefulSet had become one or a prefix scope had let something in.
        assertThat(graph.get("nodes")).hasSize(10);
        // §3 in full. Seven from `yaml`, and the last two from `connect` reading the `topics` key
        // alone (ADR-0041); `kubernetes` and `kafka` emit no edges at all (ADR-0033, ADR-0040).
        assertThat(graph.get("edges")).hasSize(9);
    }

    @Test
    void staging_drifts_by_having_no_rows_rather_than_a_flag() {
        JsonNode staging = graph("staging");
        List<String> keys = staging.findValuesAsText("key");

        assertThat(staging.get("nodes")).hasSize(7);
        assertThat(staging.get("edges")).hasSize(6);
        assertThat(keys)
                .as("ADR-0004: drift is absence. Staging lacks the Iceberg branch by having no stanza.")
                .doesNotContain("payments-iceberg-sink", "analytics.payments_events", "trino-analytics");
    }

    @Test
    void the_two_workloads_carry_an_annotated_type_and_no_display_name() {
        JsonNode graph = graph("production");

        // ADR-0091: `kubernetes` derives no type from the workload kind, so §2's `type` column is
        // true only because `topology.io/type: service` is on both Deployments. Without it both
        // would arrive typeless and render on ADR-0001's fallback descriptor — the accepted cost,
        // and the reason the annotation is not optional decoration.
        assertThat(node(graph, "payments-api").get("type").asText()).isEqualTo("service");
        assertThat(node(graph, "payments-enricher").get("type").asText()).isEqualTo("service");
        assertThat(node(graph, "payments-enricher").get("displayName").isNull()).isTrue();
    }

    @Test
    void exactly_the_four_declared_nodes_carry_a_type_and_a_display_name() {
        List<String> named = new ArrayList<>();
        graph("production").get("nodes").forEach(node -> {
            if (!node.get("type").isNull() && !node.get("displayName").isNull()) {
                named.add(node.get("key").asText());
            }
        });

        // ADR-0063: only the four declared nodes carry them, because for those `yaml` is the only
        // source. Writing `type: service` on `payments-api` would look like documentation and
        // behave like a veto over whatever `kubernetes` reports, forever.
        assertThat(named)
                .containsExactlyInAnyOrder(
                        "stripe-webhooks", "payments-events-v1", "analytics.payments_events", "trino-analytics");
    }

    @Test
    void a_node_no_plugin_has_named_still_arrives_with_nulls() {
        JsonNode topic = node(graph("production"), "payments.events.raw.v1");
        JsonNode api = node(graph("production"), "payments-api");

        // ADR-0058: the API never fabricates a value no plugin supplied. With every plugin built,
        // `type` is finally populated everywhere — `kafka` supplies the topic's — and `displayName`
        // is populated nowhere but the four declared stanzas. That asymmetry is the assertion: the
        // remaining nulls are the ones no writer chose to fill, not the ones nobody has got to yet.
        assertThat(topic.get("type").asText()).isEqualTo("kafka-topic");
        assertThat(topic.get("displayName").isNull()).isTrue();
        // ADR-0034: `kubernetes` emits no displayName on purpose — not to resolve a merge conflict
        // with YAML but to avoid manufacturing one. `payments-api`'s real YAML-supplied displayName
        // *is* the string "payments-api" on the day someone writes it, so a fallback anywhere would
        // make that indistinguishable from a node nobody has named.
        assertThat(api.get("displayName").isNull()).isTrue();
    }

    @Test
    void a_reversed_verb_is_stored_flow_directed() {
        JsonNode graph = graph("production");

        // ADR-0002: the author wrote `consumesFrom` on the enricher; the stored edge runs
        // topic -> service, so downstream is always "follow outgoing" with no orientation lookup.
        JsonNode consumes = edge(graph, "payments.events.raw.v1", "payments-enricher");
        assertThat(consumes).isNotNull();
        assertThat(consumes.get("relation").asText()).isEqualTo("CONSUMES_FROM");
        assertThat(edge(graph, "analytics.payments_events", "trino-analytics")).isNotNull();
    }

    @Test
    void consumer_groups_reach_the_node_as_a_foreign_domain_backing() {
        JsonNode backing = node(graph("production"), "payments-enricher").get("backings").get(0);

        // ADR-0013, ADR-0022: `plugin` names the technology domain the object belongs to, not the
        // plugin that emitted it. This is the routing table that will hand this node's lag to the
        // plugin that can read it, from the plugin that can attribute it.
        assertThat(backing.get("plugin").asText()).isEqualTo("kafka");
        assertThat(backing.get("kind").asText()).isEqualTo("consumer-group");
        assertThat(backing.get("reference").asText()).isEqualTo("enrich-consumer-prod");
    }

    @Test
    void descriptors_ride_with_the_graph_and_are_global() {
        JsonNode staging = graph("staging");

        // ADR-0055, ADR-0077: served inside the graph document so the vocabulary needed to render a
        // node always arrives with it, and global, so what staging receives is a superset of the
        // types staging uses. That is harmless and deliberate, not environment-scoped leakage.
        assertThat(staging.get("typeDescriptors").findValuesAsText("type"))
                .contains("external-api", "elasticsearch-index", "iceberg-table", "service");
        assertThat(staging.get("relationDescriptors")).hasSize(6);
    }

    @Test
    void every_node_carries_its_provenance_and_when_that_plugin_last_confirmed_it() {
        graph("production").get("nodes").forEach(node -> {
            JsonNode source = node.get("sources").get(0);
            assertThat(source.get("plugin").asText()).isEqualTo("yaml");
            // ADR-0056: a read-time projection over the snapshot store, never a stored field. A
            // timestamp inside the folded row would move on every poll and turn ADR-0050's
            // `updatedAt` into the poll clock it exists to prevent.
            assertThat(source.get("confirmedAt").asText()).isEqualTo("<timestamp>");
        });
    }

    @Test
    void the_outcome_block_explains_the_payload_it_rides_on() {
        JsonNode plugins = graph("production").get("plugins");

        // One row per (plugin, capability) pair configured for *discovery* — all four of them now.
        // Three also declare Health, and their rows for that are on /state rather than here:
        // ADR-0072 keeps two headers written by two loops, so the two blocks cannot disagree about a
        // shared row. The order is `registry-order`'s, which is also the order `sources[]` is
        // written in and the order rawSignal contributions join in.
        assertThat(plugins).hasSize(4);
        assertThat(plugins.findValuesAsText("plugin"))
                .containsExactly("yaml", "kubernetes", "kafka", "connect");
        plugins.forEach(plugin -> {
            assertThat(plugin.get("capability").asText()).isEqualTo("DISCOVERY");
            assertThat(plugin.get("outcome").asText()).isEqualTo("COMPLETE");
        });
    }

    @Test
    void an_unknown_environment_is_a_plain_404_in_problem_json() {
        ResponseEntity<JsonNode> response =
                http.getForEntity("/api/environments/nope/graph", JsonNode.class);

        // ADR-0060: RFC 9457, unversioned, unenveloped.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getBody().get("environmentKey").asText()).isEqualTo("nope");
    }

    @Test
    void an_error_we_did_not_raise_ourselves_is_problem_json_too() {
        ResponseEntity<JsonNode> response = http.getForEntity("/api/nope", JsonNode.class);

        // ADR-0060 says *errors* are problem+json, not "the errors we happen to throw". The frontend
        // has one error path and reads `detail` off it; a default Spring error body has no `detail`.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
    }

    @Test
    void a_node_key_carrying_dots_survives_route_matching() {
        // ADR-0052: node keys carry dots (`analytics.payments_events`), so route matching must do no
        // suffix or extension handling. No per-node route exists yet, but the environment path is
        // the same matcher and the keys ride inside the document either way.
        assertThat(node(graph("production"), "payments.events.enriched.v1")).isNotNull();
        assertThat(node(graph("production"), "analytics.payments_events")).isNotNull();
    }

    // ---------------------------------------------------------------- the merge (slice 2)

    @Test
    void three_strings_for_one_service_resolve_to_one_node_carrying_all_three() {
        JsonNode enricher = node(graph("production"), "payments-enricher");

        // The fixture's most important feature. `enricher-v2` (the Deployment) ≠ `payments-enricher`
        // (the node key) ≠ `enrich-consumer-prod` (the consumer group), and identity resolution that
        // only works on the `payments-api` case is not identity resolution. ADR-0021's annotation
        // tier does it inside the plugin; ADR-0020's flat case-folded key is what lets two plugins
        // mint it independently and land on one row.
        assertThat(enricher.get("sources").findValuesAsText("plugin")).containsExactly("yaml", "kubernetes");
        assertThat(backings(enricher))
                .containsExactly(
                        "kafka consumer-group enrich-consumer-prod",
                        "kubernetes deployment payments-prod/enricher-v2");
        // ADR-0023: there is no alias field, because all three strings are already on the node once
        // resolution and attribution have run — which is exactly the searchable set #15 needs.
        assertThat(enricher.get("key").asText()).isEqualTo("payments-enricher");
    }

    @Test
    void the_consumer_group_both_plugins_know_is_unioned_rather_than_duplicated() {
        // ADR-0022's two declaration routes — the annotation on the workload and the YAML stanza —
        // emit the same backing shape, and ADR-0044 gives `backings[]` the element identity
        // `(plugin, kind, reference)`. Both writing it is a union, not a conflict.
        assertThat(backings(node(graph("production"), "payments-enricher")))
                .filteredOn(backing -> backing.startsWith("kafka "))
                .hasSize(1);
    }

    @Test
    void payments_api_is_backed_by_its_deployment_service_and_ingress() {
        // ADR-0030: Ingress -> Service -> workload by selector, and all three are backings of the
        // *same* node. That selector match is the one Kubernetes inference that survives ADR-0033,
        // and it produces attachment rather than an edge.
        assertThat(backings(node(graph("production"), "payments-api")))
                .containsExactly(
                        "kubernetes deployment payments-prod/payments-api",
                        "kubernetes ingress payments-prod/payments-api",
                        "kubernetes service payments-prod/payments-api");
    }

    @Test
    void the_workload_hosting_both_connectors_is_not_a_node() {
        // ADR-0031, and the reason the ten-node inventory holds: `kafka-connect` is suppressed by
        // exact `kind/name`. Suppression removes node emission only, and both halves of that are now
        // visible in one document — the StatefulSet is not a node, and `connect` stamps it onto both
        // connector nodes as a backing, which is what keeps ADR-0013's routing intact.
        assertThat(graph("production").get("nodes").findValuesAsText("key")).doesNotContain("kafka-connect");
        assertThat(backings(node(graph("production"), "payments-es-sink")))
                .contains("kubernetes statefulset payments-prod/kafka-connect");
    }

    @Test
    void a_link_is_composed_per_environment_and_never_read_from_an_annotation() {
        // `topology.io/grafana: payments-enricher-overview` is a dashboard id, not a URL, and the
        // same manifest is deployed to both environments. A hardcoded URL in the annotation would
        // point staging's node at production's dashboard; the id-plus-template split is what makes
        // one annotation render correctly in both. This is ADR-0032's load-bearing claim.
        assertThat(links(node(graph("production"), "payments-enricher")))
                .contains("dashboard https://grafana.acme.io/d/payments-enricher-overview");
        assertThat(links(node(graph("staging"), "payments-enricher")))
                .contains("dashboard https://grafana-staging.acme.io/d/payments-enricher-overview");
    }

    @Test
    void argo_manages_one_of_the_two_services_and_the_links_say_so() {
        JsonNode graph = graph("production");

        // ADR-0032 reads Argo CD's own `argocd.argoproj.io/instance` label, so the fixture's
        // deliberate unevenness is explained honestly and at zero annotation cost.
        assertThat(links(node(graph, "payments-api")))
                .contains("gitops https://argocd.acme.io/applications/payments-api");
        assertThat(links(node(graph, "payments-enricher"))).noneMatch(link -> link.startsWith("gitops "));
    }

    @Test
    void the_type_the_annotation_sets_draws_with_a_descriptor_nobody_guessed() {
        JsonNode graph = graph("production");
        JsonNode service = descriptor(graph, "service");

        // The two halves of ADR-0001 meeting. `kubernetes` sets `type` from `topology.io/type` and
        // guesses no label, category or icon for it (ADR-0091: a guessed default would put a type
        // nobody chose into the global set served inside /graph), so `service`'s descriptor comes
        // from the YAML `types:` block — "registered by plugins and by the YAML topology alike"
        // doing exactly the work it was designed for. Without it the fixture's two most important
        // nodes would render on the fallback descriptor.
        assertThat(node(graph, "payments-api").get("type").asText()).isEqualTo("service");
        assertThat(service.get("source").asText()).isEqualTo("yaml");
        assertThat(service.get("label").asText()).isEqualTo("Service");
        assertThat(service.get("icon").asText()).isEqualTo("service");
    }

    @Test
    void a_verb_only_stanza_becomes_indistinguishable_from_a_described_node() {
        JsonNode api = node(graph("production"), "payments-api");

        // ADR-0063's accepted cost, felt rather than fixed (ADR-0101). `payments-api`'s YAML stanza
        // is one verb key and declares no owner, yet the node reads `sources: [yaml, kubernetes]`
        // with an ownerKey — provenance is per node, not per field, and ADR-0008 says so.
        assertThat(api.get("sources").findValuesAsText("plugin")).containsExactly("yaml", "kubernetes");
        assertThat(api.get("ownerKey").asText()).isEqualTo("payments-platform");
    }

    private static JsonNode descriptor(JsonNode graph, String type) {
        for (JsonNode descriptor : graph.get("typeDescriptors")) {
            if (descriptor.get("type").asText().equals(type)) {
                return descriptor;
            }
        }
        throw new AssertionError("no descriptor for type " + type);
    }

    private static List<String> backings(JsonNode node) {
        List<String> backings = new ArrayList<>();
        node.get("backings").forEach(backing -> backings.add("%s %s %s".formatted(
                backing.get("plugin").asText(),
                backing.get("kind").asText(),
                backing.get("reference").asText())));
        return backings;
    }

    private static List<String> links(JsonNode node) {
        List<String> links = new ArrayList<>();
        node.get("links").forEach(link ->
                links.add(link.get("rel").asText() + " " + link.get("url").asText()));
        return links;
    }

    static JsonNode node(JsonNode graph, String key) {
        for (JsonNode node : graph.get("nodes")) {
            if (node.get("key").asText().equals(key)) {
                return node;
            }
        }
        throw new AssertionError("no node keyed " + key);
    }

    private static JsonNode edge(JsonNode graph, String fromKey, String toKey) {
        for (JsonNode edge : graph.get("edges")) {
            if (edge.get("fromKey").asText().equals(fromKey) && edge.get("toKey").asText().equals(toKey)) {
                return edge;
            }
        }
        return null;
    }
}
