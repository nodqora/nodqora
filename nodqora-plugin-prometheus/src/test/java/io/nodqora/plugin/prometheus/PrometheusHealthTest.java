// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.prometheus;

import static io.nodqora.plugin.prometheus.StubPrometheus.sample;
import static org.assertj.core.api.Assertions.assertThat;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.Health;
import io.nodqora.plugin.api.HealthCapability.HealthRequest;
import io.nodqora.plugin.api.HealthCapability.HealthResult;
import io.nodqora.plugin.api.HealthCapability.ObservableNode;
import io.nodqora.plugin.api.OutcomeStatus;
import io.nodqora.plugin.api.StateContribution;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-0165 and ADR-0167 against a stubbed Prometheus: which queries are sent, and what a node is
 * given back. The query strings are asserted exactly because they <em>are</em> the recipe.
 */
class PrometheusHealthTest {

    private static final String AGGREGATOR = "app=market-aggregator,namespace=market-demo";

    private static final String STREAMS_RATE =
            "sum by (app, namespace) (kafka_stream_thread_process_rate{app=\"market-aggregator\",namespace=\"market-demo\"})";
    private static final String STREAMS_LATENCY =
            "sum by (app, namespace) (kafka_stream_thread_process_latency_avg{app=\"market-aggregator\",namespace=\"market-demo\"}"
                    + " * on (pod, thread_id, app, namespace)"
                    + " kafka_stream_thread_process_rate{app=\"market-aggregator\",namespace=\"market-demo\"})"
                    + " / sum by (app, namespace) (kafka_stream_thread_process_rate{app=\"market-aggregator\",namespace=\"market-demo\"})";
    private static final String HTTP_RATE =
            "sum by (app, namespace) (rate(http_server_requests_seconds_count{app=\"market-aggregator\",namespace=\"market-demo\",uri!~\"/actuator.*\"}[2m]))";
    private static final String HTTP_LATENCY =
            "sum by (app, namespace) (rate(http_server_requests_seconds_sum{app=\"market-aggregator\",namespace=\"market-demo\",uri!~\"/actuator.*\"}[2m]))"
                    + " / sum by (app, namespace) (rate(http_server_requests_seconds_count{app=\"market-aggregator\",namespace=\"market-demo\",uri!~\"/actuator.*\"}[2m]))"
                    + " * 1000";

    private static final Map<String, String> AGGREGATOR_LABELS = Map.of("app", "market-aggregator", "namespace", "market-demo");

    private final StubPrometheus prometheus = new StubPrometheus();
    private final PrometheusPlugin plugin = new PrometheusPlugin(new HttpPrometheusApi());

    @AfterEach
    void stop() {
        prometheus.close();
    }

    @Test
    void a_streams_node_reads_its_rate_per_second_and_mean_latency_and_never_votes() {
        prometheus.answer(STREAMS_RATE, sample(AGGREGATOR_LABELS, "34.67284567778374"));
        prometheus.answer(STREAMS_LATENCY, sample(AGGREGATOR_LABELS, "0.02844508743251564"));

        HealthResult result = observe(node("market-aggregator", streams(AGGREGATOR)));

        // ADR-0165: always UNKNOWN, and no rawSignal — a non-voter's line never reaches the card.
        assertThat(result.contributions()).containsOnly(Map.entry(
                "market-aggregator",
                new StateContribution(Health.UNKNOWN, null, Map.of("rate", 34.67284567778374, "latency", 0.02844508743251564))));
        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.COMPLETE);
    }

    @Test
    void the_http_recipe_excludes_actuator_and_reports_milliseconds() {
        prometheus.answer(HTTP_RATE, sample(AGGREGATOR_LABELS, "38"));
        prometheus.answer(HTTP_LATENCY, sample(AGGREGATOR_LABELS, "12.5"));

        HealthResult result = observe(node("market-aggregator", http(AGGREGATOR)));

        assertThat(result.contributions().get("market-aggregator").metrics())
                .containsOnly(Map.entry("rate", 38.0), Map.entry("latency", 12.5));
    }

    @Test
    void a_binding_that_matches_no_series_is_an_omission() {
        HealthResult result = observe(node("market-aggregator", streams(AGGREGATOR)));

        // ADR-0167's silence: not UNKNOWN-with-nothing, which the store would drop anyway, but absent.
        assertThat(result.contributions()).isEmpty();
        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.COMPLETE);
    }

    @Test
    void idle_series_report_rate_zero_and_no_latency() {
        // 0/0 is NaN in PromQL. The rate is a measurement; the mean of no work is not a number.
        prometheus.answer(STREAMS_RATE, sample(AGGREGATOR_LABELS, "0"));
        prometheus.answer(STREAMS_LATENCY, sample(AGGREGATOR_LABELS, "NaN"));

        HealthResult result = observe(node("market-aggregator", streams(AGGREGATOR)));

        assertThat(result.contributions().get("market-aggregator").metrics()).containsOnly(Map.entry("rate", 0.0));
    }

    @Test
    void streams_wins_over_http_when_both_yield() {
        prometheus.answer(STREAMS_RATE, sample(AGGREGATOR_LABELS, "34"));
        prometheus.answer(STREAMS_LATENCY, sample(AGGREGATOR_LABELS, "0.03"));
        prometheus.answer(HTTP_RATE, sample(AGGREGATOR_LABELS, "0.2"));
        prometheus.answer(HTTP_LATENCY, sample(AGGREGATOR_LABELS, "4"));

        HealthResult result = observe(node("market-aggregator", http(AGGREGATOR), streams(AGGREGATOR)));

        assertThat(result.contributions().get("market-aggregator").metrics())
                .containsOnly(Map.entry("rate", 34.0), Map.entry("latency", 0.03));
    }

    @Test
    void http_supplies_both_keys_when_streams_yields_nothing() {
        prometheus.answer(HTTP_RATE, sample(AGGREGATOR_LABELS, "0.2"));
        prometheus.answer(HTTP_LATENCY, sample(AGGREGATOR_LABELS, "4"));

        HealthResult result = observe(node("market-aggregator", streams(AGGREGATOR), http(AGGREGATOR)));

        assertThat(result.contributions().get("market-aggregator").metrics())
                .containsOnly(Map.entry("rate", 0.2), Map.entry("latency", 4.0));
    }

    @Test
    void keys_from_two_recipes_are_never_mixed() {
        // Streams yields a latency and no rate; HTTP yields a rate. The first recipe to yield any key
        // supplies both, so the card never shows HTTP requests/s beside Streams milliseconds.
        prometheus.answer(STREAMS_LATENCY, sample(AGGREGATOR_LABELS, "0.03"));
        prometheus.answer(HTTP_RATE, sample(AGGREGATOR_LABELS, "0.2"));

        HealthResult result = observe(node("market-aggregator", streams(AGGREGATOR), http(AGGREGATOR)));

        assertThat(result.contributions().get("market-aggregator").metrics()).containsOnly(Map.entry("latency", 0.03));
    }

    @Test
    void workloads_sharing_label_names_are_read_by_one_query_per_key_and_split_exactly() {
        String matcher = "app=~\"market-aggregator|market\\\\.enricher\",namespace=\"market-demo\"";
        prometheus.answer(
                "sum by (app, namespace) (kafka_stream_thread_process_rate{%s})".formatted(matcher),
                sample(AGGREGATOR_LABELS, "34"),
                sample(Map.of("app", "market.enricher", "namespace", "market-demo"), "7"),
                // Matched by the alternation but bound to no node: read and discarded.
                sample(Map.of("app", "market-aggregator", "namespace", "elsewhere"), "99"));

        HealthResult result = observe(
                node("market-aggregator", streams(AGGREGATOR)),
                node("market-enricher", streams("app=market.enricher,namespace=market-demo")));

        // ADR-0013: queries scale with recipes × label-name sets × keys, not with workloads. The
        // value is regex-escaped inside an anchored alternation, so `.` cannot match `x`.
        assertThat(prometheus.queries()).hasSize(2);
        assertThat(result.contributions().get("market-aggregator").metrics()).containsOnly(Map.entry("rate", 34.0));
        assertThat(result.contributions().get("market-enricher").metrics()).containsOnly(Map.entry("rate", 7.0));
    }

    @Test
    void an_unknown_recipe_or_an_unparseable_selector_abstains_without_a_query() {
        HealthResult result = observe(
                node("a", new Backing("prometheus", "opentelemetry-http", "app=a")),
                node("b", new Backing("prometheus", "kafka-streams", "app={name}")),
                node("c", new Backing("prometheus", "kafka-streams", "")));

        assertThat(result.contributions()).isEmpty();
        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.COMPLETE);
        assertThat(prometheus.queries()).isEmpty();
    }

    @Test
    void backings_of_other_plugins_are_not_read() {
        HealthResult result = observe(node("market-aggregator", new Backing("kubernetes", "deployment", "market-demo/market-aggregator")));

        assertThat(result.contributions()).isEmpty();
        assertThat(prometheus.queries()).isEmpty();
    }

    @Test
    void no_routed_nodes_costs_no_round_trip() {
        assertThat(observe().outcome().status()).isEqualTo(OutcomeStatus.COMPLETE);
        assertThat(prometheus.queries()).isEmpty();
    }

    @Test
    void a_failed_query_is_partial_and_never_falls_through_to_the_next_recipe() {
        prometheus.answerRaw(STREAMS_RATE, 503, "unavailable");
        prometheus.answer(HTTP_RATE, sample(AGGREGATOR_LABELS, "0.2"));
        prometheus.answer(
                "sum by (job) (kafka_stream_thread_process_rate{job=\"other\"})",
                sample(Map.of("job", "other"), "5"));

        HealthResult result = observe(
                node("market-aggregator", streams(AGGREGATOR), http(AGGREGATOR)),
                node("other", streams("job=other")));

        // Whether Streams would have yielded is unknown, so HTTP's numbers would be a guess about
        // precedence. The node is left out, and PARTIAL keeps its last reading standing (ADR-0046).
        assertThat(result.contributions()).containsOnlyKeys("other");
        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.PARTIAL);
        assertThat(result.outcome().reasons()).singleElement().asString()
                .contains("kafka-streams")
                .contains("HTTP 503");
    }

    @Test
    void every_query_failing_is_failed() {
        String url = prometheus.url();
        prometheus.close();

        HealthResult result = plugin.observe(new HealthRequest<>(
                "homelab", List.of(node("market-aggregator", streams(AGGREGATOR))), new PrometheusConfig(url, null, null)));

        assertThat(result.contributions()).isEmpty();
        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.FAILED);
        assertThat(result.outcome().reasons()).singleElement().asString().contains("unreachable");
    }

    @Test
    void of_two_bindings_for_one_recipe_the_first_by_selector_that_yields_supplies_the_node() {
        prometheus.answer(
                "sum by (app) (kafka_stream_thread_process_rate{app=\"b\"})", sample(Map.of("app", "b"), "2"));
        prometheus.answer(
                "sum by (job) (kafka_stream_thread_process_rate{job=\"a\"})", sample(Map.of("job", "a"), "1"));

        HealthResult result = observe(node("n", streams("job=a"), streams("app=b")));

        assertThat(result.contributions().get("n").metrics()).containsOnly(Map.entry("rate", 2.0));
    }

    private HealthResult observe(ObservableNode... nodes) {
        return plugin.observe(new HealthRequest<>(
                "homelab", List.of(nodes), new PrometheusConfig(prometheus.url(), null, null)));
    }

    private static ObservableNode node(String key, Backing... backings) {
        return new ObservableNode(key, List.of(backings));
    }

    private static Backing streams(String selector) {
        return new Backing("prometheus", "kafka-streams", selector);
    }

    private static Backing http(String selector) {
        return new Backing("prometheus", "micrometer-http", selector);
    }
}
