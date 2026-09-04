package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                .as("`yaml` alone renders the reference pipeline: no cluster, no broker, no worker")
                .isEqualTo(Golden.read(mapper, "graph-" + environmentKey + ".json"));
    }

    @Test
    void production_is_ten_nodes_and_yamls_seven_edges() {
        JsonNode graph = graph("production");

        assertThat(graph.get("nodes")).hasSize(10);
        // Seven of §3's nine. The two SOURCES_FROM edges are `connect`'s (ADR-0041) and arrive in
        // slice 4; `kubernetes` emits no edges at all (ADR-0033), so the split is exactly 7 + 2.
        assertThat(graph.get("edges")).hasSize(7);
    }

    @Test
    void staging_drifts_by_having_no_rows_rather_than_a_flag() {
        JsonNode staging = graph("staging");
        List<String> keys = staging.findValuesAsText("key");

        assertThat(staging.get("nodes")).hasSize(7);
        assertThat(staging.get("edges")).hasSize(5);
        assertThat(keys)
                .as("ADR-0004: drift is absence. Staging lacks the Iceberg branch by having no stanza.")
                .doesNotContain("payments-iceberg-sink", "analytics.payments_events", "trino-analytics");
    }

    @Test
    void exactly_the_four_declared_nodes_carry_a_type_and_a_display_name() {
        List<String> named = new java.util.ArrayList<>();
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
    void a_discovered_node_arrives_with_nulls_and_renders_on_the_fallback_descriptor() {
        JsonNode api = node(graph("production"), "payments-api");

        // ADR-0058: the API never fabricates a value no plugin supplied. `payments-api`'s real
        // YAML-supplied displayName *is* the string "payments-api" on the day someone writes it, so
        // a server-side fallback would make that indistinguishable from a node nobody has named.
        assertThat(api.get("type").isNull()).isTrue();
        assertThat(api.get("displayName").isNull()).isTrue();
        assertThat(api.get("ownerKey").isNull()).isTrue();
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
                .contains("external-api", "elasticsearch-index", "iceberg-table");
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

        assertThat(plugins).hasSize(1);
        assertThat(plugins.get(0).get("plugin").asText()).isEqualTo("yaml");
        assertThat(plugins.get(0).get("capability").asText()).isEqualTo("DISCOVERY");
        assertThat(plugins.get(0).get("outcome").asText()).isEqualTo("COMPLETE");
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
