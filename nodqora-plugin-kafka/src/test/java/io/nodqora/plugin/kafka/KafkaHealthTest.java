// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.Health;
import io.nodqora.plugin.api.HealthCapability.HealthRequest;
import io.nodqora.plugin.api.HealthCapability.HealthResult;
import io.nodqora.plugin.api.HealthCapability.ObservableNode;
import io.nodqora.plugin.api.OutcomeStatus;
import io.nodqora.plugin.api.StateContribution;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code kafka} plugin's health half: ADR-0025's lag arithmetic and ADR-0040's routed group set,
 * over the recorded cluster.
 */
class KafkaHealthTest {

    private static final KafkaConfig CONFIG = config(10_000L, Map.of());

    /** The fixture's five routed nodes, exactly as the engine hands them over. */
    private static List<ObservableNode> pipeline() {
        return List.of(
                group("payments-enricher", "enrich-consumer-prod"),
                group("payments-es-sink", "connect-payments-es-sink"),
                group("payments-iceberg-sink", "connect-payments-iceberg-sink"),
                topic("payments.events.raw.v1"),
                topic("payments.events.enriched.v1"));
    }

    @Test
    void a_service_gets_its_lag_through_a_backing_another_plugin_wrote() {
        StateContribution enricher = observe(new RecordedKafkaApi()).get("payments-enricher");

        // ADR-0022 from the reading side. `enrich-consumer-prod` reaches this node because
        // `kubernetes` read `topology.io/consumer-groups` off a Deployment named `enricher-v2` —
        // three different strings for one service, and this plugin resolves none of them.
        assertThat(enricher.health()).isEqualTo(Health.DEGRADED);
        assertThat(enricher.rawSignal()).isEqualTo("lag 40000");
        assertThat(enricher.metrics()).containsOnly(Map.entry("maxConsumerLag", 40_000L));
    }

    @Test
    void a_topic_borrows_the_health_of_the_groups_committing_on_it() {
        Map<String, StateContribution> observed = observe(new RecordedKafkaApi());

        // ADR-0025: a topic has no readiness of its own, so its health is the collapse of the
        // healths of the groups committing offsets on it. This is the one place `kafka` attributes
        // without help — committed offsets name `(group, topic-partition)` directly — and ADR-0022
        // is untouched, because the group was still routed here by somebody else.
        assertThat(observed.get("payments.events.raw.v1").health()).isEqualTo(Health.DEGRADED);
        assertThat(observed.get("payments.events.enriched.v1").health()).isEqualTo(Health.HEALTHY);
    }

    @Test
    void a_topic_two_groups_read_reports_the_maximum_and_says_it_is_one() {
        StateContribution enriched = observe(new RecordedKafkaApi()).get("payments.events.enriched.v1");

        // ADR-0028: `maxConsumerLag`, and max rather than sum — a summed lag over two groups is a
        // number describing nothing that exists. 8,400 is the Iceberg sink's; the ES sink's 120 is
        // the other, and neither reaches the threshold.
        assertThat(enriched.metrics()).containsOnly(Map.entry("maxConsumerLag", 8_400L));
        assertThat(enriched.rawSignal()).isEqualTo("max lag 8400 over 2 groups");
    }

    @Test
    void a_topic_carries_the_lag_on_itself_and_not_the_groups_worst_elsewhere() {
        // One Streams application reading two topics — ordinary, and absent from the reference
        // pipeline, where every group reads exactly one topic. `busy` is 1,200 behind and over the
        // threshold; `quiet` is 26 behind and nowhere near it.
        ObservedGroup aggregator = new ObservedGroup(
                "market-aggregator",
                List.of(
                        new PartitionOffsets("market.trades.busy", 0, 0L, 1_200L),
                        new PartitionOffsets("market.trades.quiet", 0, 0L, 26L)));
        List<ObservableNode> nodes = List.of(
                group("market-aggregator", "market-aggregator"),
                topic("market.trades.busy"),
                topic("market.trades.quiet"));

        Map<String, StateContribution> observed = observe(api(aggregator), nodes, config(1_000L, Map.of()));

        // The service is behind by its worst partition wherever that partition is: it is the thing
        // that has to catch up on all of them.
        assertThat(observed.get("market-aggregator").health()).isEqualTo(Health.DEGRADED);
        assertThat(observed.get("market-aggregator").rawSignal()).isEqualTo("lag 1200");

        // The topics are behind by what is behind *on them*. Reporting the group's 1,200 on the
        // quiet topic would turn it amber for a backlog it does not have, and send whoever is
        // paging to a topic with 26 records outstanding.
        assertThat(observed.get("market.trades.busy").health()).isEqualTo(Health.DEGRADED);
        assertThat(observed.get("market.trades.busy").rawSignal()).isEqualTo("lag 1200");
        assertThat(observed.get("market.trades.quiet").health()).isEqualTo(Health.HEALTHY);
        assertThat(observed.get("market.trades.quiet").rawSignal()).isEqualTo("lag 26");
        assertThat(observed.get("market.trades.quiet").metrics())
                .containsOnly(Map.entry("maxConsumerLag", 26L));
    }

    @Test
    void lag_never_reads_unhealthy_not_even_at_two_point_one_million() {
        StateContribution enricher = observe(RecordedKafkaApi.scenario("incident")).get("payments-enricher");

        // ADR-0025, and the whole reason there is one threshold rather than a pair. *Lag means
        // behind, not broken*: a consumer that is behind is still doing its job, and broken-ness
        // arrives from the workload crash-looping — which under this scenario it is, so the node
        // reads UNHEALTHY on the composed row while this plugin's own vote stays DEGRADED.
        assertThat(enricher.health()).isEqualTo(Health.DEGRADED);
        assertThat(enricher.metrics()).containsOnly(Map.entry("maxConsumerLag", 2_100_000L));
    }

    @Test
    void the_sink_groups_lag_is_frozen_under_the_incident_so_the_topic_stays_healthy() {
        Map<String, StateContribution> incident = observe(RecordedKafkaApi.scenario("incident"));

        // §8's amended row. Under the incident nothing produces to `enriched.v1` *and* both sinks
        // are paused, so the sink groups' lag stays at its baseline and the topic reads HEALTHY. An
        // idle topic reading HEALTHY is honest: its original signal, "no new records for 20m", is
        // producer staleness, computable only by comparing high watermarks across two polls — and
        // ADR-0012 makes plugins stateless.
        assertThat(incident.get("payments.events.enriched.v1").health()).isEqualTo(Health.HEALTHY);
        assertThat(incident.get("payments.events.raw.v1").health()).isEqualTo(Health.DEGRADED);
    }

    @Test
    void the_threshold_is_per_group_and_the_default_is_what_a_group_without_one_uses() {
        Map<String, StateContribution> lenient =
                observe(new RecordedKafkaApi(), config(10_000L, Map.of("enrich-consumer-prod", 50_000L)));

        // ADR-0025: keyed by consumer group, not by node — the core never sees a threshold
        // (ADR-0015) and this plugin never needs to know whose lag it is (ADR-0022). Raising the
        // enricher's own limit past 40,000 turns it green and leaves the two sinks alone.
        assertThat(lenient.get("payments-enricher").health()).isEqualTo(Health.HEALTHY);
        assertThat(lenient.get("payments.events.raw.v1").health()).isEqualTo(Health.HEALTHY);
        assertThat(lenient.get("payments.events.enriched.v1").health()).isEqualTo(Health.HEALTHY);
    }

    @Test
    void aggregation_is_over_health_and_never_over_lag() {
        // Two groups on one topic, each at its own threshold: the quiet one is over its own limit,
        // the loud one is under its own. Collapsing the *lag* first would evaluate 9,000 against
        // whichever threshold happened to be picked and get one of the two answers wrong.
        Map<String, StateContribution> observed = observe(
                api(
                        group("quiet", "t", 500),
                        group("loud", "t", 9_000)),
                // Both groups have to be routed by *something* before this plugin will read them
                // (ADR-0040), so the two consumers carrying them sit beside the topic.
                List.of(group("quiet-consumer", "quiet"), group("loud-consumer", "loud"), topic("t")),
                config(1_000_000L, Map.of("quiet", 100L, "loud", 1_000_000L)));

        assertThat(observed.get("t").health()).isEqualTo(Health.DEGRADED);
        // The metric is still the max lag, which is the loud group's — the healthy one.
        assertThat(observed.get("t").metrics()).containsOnly(Map.entry("maxConsumerLag", 9_000L));
    }

    @Test
    void a_partition_with_no_committed_offset_is_skipped_rather_than_counted_as_zero() {
        Map<String, StateContribution> observed = observe(
                api(new ObservedGroup(
                        "g",
                        List.of(
                                new PartitionOffsets("t", 0, null, 5_000_000L),
                                new PartitionOffsets("t", 1, 40_000L, 60_000L)))),
                List.of(group("consumer", "g"), topic("t")),
                CONFIG);

        // ADR-0025's caveat from research #5. A group that has not started is not a group that is
        // caught up — counting the uncommitted partition as zero would read 5,000,000 of lag off a
        // partition nobody has consumed yet.
        assertThat(observed.get("t").metrics()).containsOnly(Map.entry("maxConsumerLag", 20_000L));
    }

    @Test
    void a_negative_lag_clamps_to_zero_and_reads_healthy() {
        Map<String, StateContribution> observed = observe(
                api(new ObservedGroup("g", List.of(new PartitionOffsets("t", 0, 900L, 880L)))),
                List.of(group("consumer", "g"), topic("t")),
                CONFIG);

        // A committed offset compared against a high watermark sampled a moment earlier legitimately
        // goes negative. Clamping is the honest reading; a negative number on the overlay is not.
        assertThat(observed.get("t").health()).isEqualTo(Health.HEALTHY);
        assertThat(observed.get("t").metrics()).containsOnly(Map.entry("maxConsumerLag", 0L));
    }

    @Test
    void a_group_that_committed_nothing_at_all_abstains() {
        Map<String, StateContribution> observed = observe(
                api(new ObservedGroup("g", List.of())), List.of(group("service", "g")), CONFIG);

        // An abstention is an omission (ADR-0104). Returning UNKNOWN for something observed would
        // delete this plugin's own vote under ADR-0024 — it means "I was not asked" or "I could not
        // look", and nothing else.
        assertThat(observed).isEmpty();
    }

    @Test
    void a_topic_with_no_routed_group_is_unknown_rather_than_healthy() {
        HealthResult result = new KafkaPlugin(new RecordedKafkaApi())
                .observe(new HealthRequest<>("production", List.of(topic("payments.events.raw.v1")), CONFIG));

        // ADR-0040: zero groups is zero signal, and ADR-0024 makes UNKNOWN an abstention that is
        // discarded. Reporting HEALTHY from no signal is exactly what ADR-0026 split `outcome` from
        // `health` to prevent — and here it also means no round trip, since nothing routed a group.
        assertThat(result.contributions()).isEmpty();
        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.COMPLETE);
    }

    @Test
    void only_the_routed_groups_are_ever_asked_for() {
        List<List<String>> asked = new ArrayList<>();
        KafkaApi recording = new KafkaApi() {
            private final RecordedKafkaApi delegate = new RecordedKafkaApi();

            @Override
            public List<String> listTopics(KafkaConfig config) {
                return delegate.listTopics(config);
            }

            @Override
            public List<ObservedTopic> describeTopics(KafkaConfig config, List<String> names) {
                return delegate.describeTopics(config, names);
            }

            @Override
            public List<ObservedGroup> consumerGroupOffsets(KafkaConfig config, List<String> groupIds) {
                asked.add(groupIds);
                return delegate.consumerGroupOffsets(config, groupIds);
            }
        };

        observe(recording);

        // ADR-0040. `orders-consumer-prod` sits in the recording and is never asked for, because
        // nothing routed it — which is the whole difference between reading the routed set and
        // enumerating the cluster's. `listGroups` has no method on the seam to be called through.
        assertThat(asked)
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactly(
                        "enrich-consumer-prod", "connect-payments-es-sink", "connect-payments-iceberg-sink");
    }

    @Test
    void an_unreachable_cluster_is_failed_and_leaves_the_store_alone() {
        HealthResult result = new KafkaPlugin(new KafkaApi() {
                    @Override
                    public List<String> listTopics(KafkaConfig config) {
                        throw new KafkaApiException("no brokers available");
                    }

                    @Override
                    public List<ObservedTopic> describeTopics(KafkaConfig config, List<String> names) {
                        throw new KafkaApiException("no brokers available");
                    }

                    @Override
                    public List<ObservedGroup> consumerGroupOffsets(KafkaConfig config, List<String> groupIds) {
                        throw new KafkaApiException("no brokers available");
                    }
                })
                .observe(new HealthRequest<>("production", pipeline(), CONFIG));

        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.FAILED);
        assertThat(result.contributions()).isEmpty();
    }

    @Test
    void disabled_is_never_emitted_whatever_the_lag() {
        Map<String, StateContribution> baseline = observe(new RecordedKafkaApi());
        Map<String, StateContribution> incident = observe(RecordedKafkaApi.scenario("incident"));

        // ADR-0029: an EMPTY group is the exact state of a deliberately scaled-down consumer and of
        // a crashed one alike, and DISABLED requires evidence of intent. `kubernetes` may say it
        // because `spec.replicas: 0` is declarative; nothing this plugin can see is.
        assertThat(baseline.values()).noneMatch(state -> state.health() == Health.DISABLED);
        assertThat(incident.values()).noneMatch(state -> state.health() == Health.DISABLED);
    }

    // ---------------------------------------------------------------- helpers

    private static KafkaConfig config(long defaultThreshold, Map<String, Long> perGroup) {
        return new KafkaConfig(
                "kafka-prod.internal:9092",
                null,
                new KafkaConfig.Topics(List.of("payments."), List.of("connect-offsets")),
                new KafkaConfig.Lag(defaultThreshold, perGroup),
                null);
    }

    private static Map<String, StateContribution> observe(KafkaApi api) {
        return observe(api, CONFIG);
    }

    private static Map<String, StateContribution> observe(KafkaApi api, KafkaConfig config) {
        return observe(api, pipeline(), config);
    }

    private static Map<String, StateContribution> observe(
            KafkaApi api, List<ObservableNode> nodes, KafkaConfig config) {
        return new KafkaPlugin(api)
                .observe(new HealthRequest<>("production", nodes, config))
                .contributions();
    }

    private static ObservableNode group(String key, String groupId) {
        return new ObservableNode(key, List.of(new Backing("kafka", "consumer-group", groupId)));
    }

    private static ObservableNode topic(String key) {
        return new ObservableNode(key, List.of(new Backing("kafka", "topic", key)));
    }

    /** One partition, so the lag is the number given. */
    private static ObservedGroup group(String groupId, String topic, long lag) {
        return new ObservedGroup(groupId, List.of(new PartitionOffsets(topic, 0, 0L, lag)));
    }

    private static KafkaApi api(ObservedGroup... groups) {
        return new KafkaApi() {
            @Override
            public List<String> listTopics(KafkaConfig config) {
                return List.of();
            }

            @Override
            public List<ObservedTopic> describeTopics(KafkaConfig config, List<String> names) {
                return List.of();
            }

            @Override
            public List<ObservedGroup> consumerGroupOffsets(KafkaConfig config, List<String> groupIds) {
                return List.of(groups);
            }
        };
    }
}
