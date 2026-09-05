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
 * The slice's done-when: §8's <b>baseline</b> scenario, read off the real endpoint, against
 * ADR-0099's golden documents — for the observer set that exists.
 *
 * <p>Only {@code kubernetes} declares Health in this slice, so the goldens assert §8's baseline
 * column <em>restricted to the Kubernetes signal</em>: {@code payments-api} HEALTHY,
 * {@code payments-enricher} DEGRADED, everything else UNKNOWN. The four values §8 derives from lag
 * and connector state — both topics and both connectors — are not weakened here, they are
 * <b>absent</b>, and absence is the honest rendering of a plugin that has not been built. Slice 4
 * adds them, and the diff on these files is the review surface for whether ADR-0024's collapse did
 * what it says: {@code payments-enricher} must go from DEGRADED-by-one-observer to
 * DEGRADED-by-two, and {@code payments-iceberg-sink} from UNKNOWN to DEGRADED.
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
                .as("§8 baseline, for the `kubernetes`-only observer set")
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
        assertThat(node(state, "payments-enricher").get("rawSignal").asText()).isEqualTo("3 desired / 2 ready");
    }

    @Test
    void a_node_nobody_observes_is_unknown_by_arithmetic_rather_than_by_rule() {
        JsonNode state = state("production");

        // ADR-0028: no contributions, no row, and the outer join synthesizes all four fields. Six of
        // the ten production nodes take that path in this slice — four permanently (nothing will
        // ever observe them) and two waiting for `kafka` and `connect`.
        JsonNode declared = node(state, "trino-analytics");
        assertThat(declared.get("health").asText()).isEqualTo("UNKNOWN");
        assertThat(declared.get("rawSignal").isNull()).isTrue();
        assertThat(declared.get("metrics")).isEmpty();
        assertThat(declared.get("observedAt").isNull()).isTrue();

        assertThat(unknownKeys(state))
                .containsExactlyInAnyOrder(
                        "stripe-webhooks",
                        "payments.events.raw.v1",
                        "payments.events.enriched.v1",
                        "payments-es-sink",
                        "payments-iceberg-sink",
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
        assertThat(metrics.fieldNames()).toIterable().containsExactly("kubernetes");
        assertThat(metrics.get("kubernetes").fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("desiredReplicas", "readyReplicas");
        assertThat(metrics.get("kubernetes").get("desiredReplicas").asInt()).isEqualTo(3);
        assertThat(metrics.get("kubernetes").get("readyReplicas").asInt()).isEqualTo(2);
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

        // ADR-0085: a *config* roster, so this has one row because one configured plugin declares
        // Health — not because only one has reported. ADR-0072: two headers, written by different
        // loops, so /graph's block and this one cannot disagree by construction.
        assertThat(plugins).hasSize(1);
        assertThat(plugins.get(0).get("plugin").asText()).isEqualTo("kubernetes");
        assertThat(plugins.get(0).get("capability").asText()).isEqualTo("HEALTH");
        assertThat(plugins.get(0).get("outcome").asText()).isEqualTo("COMPLETE");
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
