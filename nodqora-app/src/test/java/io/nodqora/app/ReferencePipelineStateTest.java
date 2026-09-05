// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * The slice's done-when: §8's <b>baseline</b> scenario in full, read off the real endpoint, against
 * ADR-0099's golden documents.
 *
 * <p>Slice 2 asserted this column restricted to the Kubernetes signal and named the diff these files
 * would show once the other two observers arrived. They did, and it did: {@code payments-enricher}
 * went from DEGRADED-by-one-observer to DEGRADED-by-two, and {@code payments-iceberg-sink} from
 * UNKNOWN to DEGRADED. Every one of §8's five normalized states is now produced by a plugin rather
 * than by an absence, which is the difference between a fixture the MVP reproduces and one it merely
 * does not contradict.
 */
class ReferencePipelineStateTest extends NodqoraIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper mapper;

    private JsonNode state(String environmentKey) {
        return Golden.normalize(
                http.getForObject("/api/environments/" + environmentKey + "/state", JsonNode.class));
    }

    @Test
    void the_production_state_document_matches_its_golden() {
        assertThat(state("production"))
                .as("§8 baseline, over all three observers")
                .isEqualTo(Golden.read(mapper, "state-production-baseline.json"));
    }

    @Test
    void the_staging_state_document_matches_its_golden() {
        // §8: "Staging under baseline: all seven nodes HEALTHY or UNKNOWN, no lag." Staging's
        // enricher is 3/3 rather than production's 3/2, which is the whole difference between the
        // two documents — the fixture's one interesting workload is a production fact.
        assertThat(state("staging")).isEqualTo(Golden.read(mapper, "state-staging-baseline.json"));
    }

    @Test
    void readiness_arithmetic_is_all_some_none_and_not_status_conditions() {
        JsonNode state = state("production");

        // ADR-0025's load-bearing choice. `Available=True` holds at 2 of 3 replicas, so reading
        // `status.conditions` would render `enricher-v2` HEALTHY and erase the one Kubernetes signal
        // the whole fixture is built around. A tool whose job is "what does this look like right
        // now" says two of three when two of three are up.
        assertThat(health(state, "payments-api")).isEqualTo("HEALTHY");
        assertThat(health(state, "payments-enricher")).isEqualTo("DEGRADED");
        // ADR-0028: the signal is joined in registry order and each writer names only what it saw.
        // Two observers now agree the enricher is DEGRADED for two unrelated reasons, and the
        // operator gets both rather than the winner — a composed `health` says which colour to draw,
        // and this string is the only place the reason survives.
        assertThat(node(state, "payments-enricher").get("rawSignal").asText())
                .isEqualTo("3 desired / 2 ready; lag 40000");
    }

    @Test
    void a_node_nobody_observes_is_unknown_by_arithmetic_rather_than_by_rule() {
        JsonNode state = state("production");

        // ADR-0028: no contributions, no row, and the outer join synthesizes all four fields. With
        // every plugin built, exactly §2's four declared nodes take that path, and they take it
        // permanently — nothing in the MVP will ever observe an Elasticsearch index, an Iceberg
        // table, a Trino cluster or a third party's webhook. That UNKNOWN is now a fact about the
        // pipeline rather than a fact about the roster is the whole of what slice 4 changed here.
        JsonNode declared = node(state, "trino-analytics");
        assertThat(declared.get("health").asText()).isEqualTo("UNKNOWN");
        assertThat(declared.get("rawSignal").isNull()).isTrue();
        assertThat(declared.get("metrics")).isEmpty();
        assertThat(declared.get("observedAt").isNull()).isTrue();

        assertThat(unknownKeys(state))
                .containsExactlyInAnyOrder(
                        "stripe-webhooks",
                        "payments-events-v1",
                        "analytics.payments_events",
                        "trino-analytics");
    }

    @Test
    void state_carries_every_node_key_including_the_ones_nothing_observes() {
        JsonNode graph = http.getForObject("/api/environments/production/graph", JsonNode.class);
        JsonNode state = state("production");

        // ADR-0057: absence is already load-bearing and already means something else — a key missing
        // from /graph means *deleted*. Two payloads polled at different rates, in which a missing key
        // means "deleted" in one and "fine, just unwatched" in the other, is a bug waiting for its
        // first incident. Now that some keys genuinely carry state, this is the assertion that keeps
        // the cheap optimisation of omitting the rest from creeping back in.
        assertThat(state.get("nodes").findValuesAsText("nodeKey"))
                .containsExactlyElementsOf(graph.get("nodes").findValuesAsText("key"));
    }

    @Test
    void metrics_are_namespaced_by_plugin_and_allow_listed_by_name() {
        JsonNode metrics = node(state("production"), "payments-enricher").get("metrics");

        // ADR-0006: the plugin's own namespace, so no plugin can write into another's and the
        // allow-listed key names never collide. ADR-0028: enumerated by name, never "whatever the
        // API returned" — these two keys and no others.
        //
        // Order-insensitive on purpose, and it is the one place in this suite that is. ADR-0050's
        // ordering obligation is about *collections*, which `jsonb` stores as written; the key order
        // of an *object* is `jsonb`'s own (by length, then bytes) and it overwrites whatever the fold
        // chose. Asserting our order here would assert a thing the storage layer does not preserve.
        // The golden still pins the order that actually ships.
        assertThat(metrics.fieldNames()).toIterable().containsExactlyInAnyOrder("kubernetes", "kafka");
        assertThat(metrics.get("kubernetes").fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("desiredReplicas", "readyReplicas");
        assertThat(metrics.get("kubernetes").get("desiredReplicas").asInt()).isEqualTo(3);
        assertThat(metrics.get("kubernetes").get("readyReplicas").asInt()).isEqualTo(2);
        // The namespacing earning its keep: two plugins write to one node's metrics and neither can
        // reach the other's object. ADR-0038 is why `kafka`'s namespace holds one key and not the
        // throughput and topic size an operator would ask for first — both are diffs across polls,
        // which ADR-0012 forbids a stateless plugin from taking.
        assertThat(metrics.get("kafka").fieldNames()).toIterable().containsExactly("maxConsumerLag");
        assertThat(metrics.get("kafka").get("maxConsumerLag").asInt()).isEqualTo(40000);
    }

    @Test
    void only_workload_backings_contribute_and_the_service_and_ingress_are_inert() {
        JsonNode graph = http.getForObject("/api/environments/production/graph", JsonNode.class);
        JsonNode api = ReferencePipelineGraphTest.node(graph, "payments-api");

        // ADR-0034: `payments-api` carries three `kubernetes` backings — Deployment, Service,
        // Ingress — and exactly one of them has a readiness concept. If the other two contributed,
        // two abstentions would be discarded by ADR-0024 and the answer would be the same; the
        // assertion that catches a real mistake is the metric, which would double or triple.
        assertThat(api.get("backings")).hasSize(3);
        JsonNode metrics = node(state("production"), "payments-api").get("metrics").get("kubernetes");
        assertThat(metrics.get("desiredReplicas").asInt()).isEqualTo(3);
        assertThat(metrics.get("readyReplicas").asInt()).isEqualTo(3);
    }

    @Test
    void the_outcome_block_reports_the_health_roster_and_not_the_discovery_one() {
        JsonNode plugins = state("production").get("plugins");

        // ADR-0085: a *config* roster, so this has three rows because three configured plugins
        // declare Health — not because three have reported. It is one shorter than /graph's four,
        // and the missing one is `yaml`, which declares Discovery and never Health (ADR-0011). That
        // the two blocks differ is the point: ADR-0072 has them written by different loops, so they
        // cannot disagree about a shared row by construction.
        assertThat(plugins).hasSize(3);
        assertThat(plugins.findValuesAsText("plugin")).containsExactly("kubernetes", "kafka", "connect");
        plugins.forEach(plugin -> {
            assertThat(plugin.get("capability").asText()).isEqualTo("HEALTH");
            assertThat(plugin.get("outcome").asText()).isEqualTo("COMPLETE");
        });
    }

    @Test
    void the_documents_freshness_is_the_stalest_observation_it_carries() {
        JsonNode state = state("production");

        // ADR-0072: `min`, never `max` — the frontend renders this as "how old is this", so it must
        // not overstate. Unobserved nodes contribute no timestamp at all rather than a null that
        // would sort first and freeze the document at the epoch.
        assertThat(state.get("observedAt").asText()).isEqualTo("<timestamp>");
        assertThat(node(state, "trino-analytics").get("observedAt").isNull()).isTrue();
    }

    static JsonNode node(JsonNode state, String key) {
        for (JsonNode node : state.get("nodes")) {
            if (node.get("nodeKey").asText().equals(key)) {
                return node;
            }
        }
        throw new AssertionError("no state for node " + key);
    }

    private static String health(JsonNode state, String key) {
        return node(state, key).get("health").asText();
    }

    private static List<String> unknownKeys(JsonNode state) {
        List<String> keys = new ArrayList<>();
        state.get("nodes").forEach(node -> {
            if (node.get("health").asText().equals("UNKNOWN")) {
                keys.add(node.get("nodeKey").asText());
            }
        });
        return keys;
    }
}
