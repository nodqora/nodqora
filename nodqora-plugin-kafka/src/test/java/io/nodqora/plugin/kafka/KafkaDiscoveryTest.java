// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.DiscoveredNode;
import io.nodqora.plugin.api.DiscoveryRequest;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.Link;
import io.nodqora.plugin.api.OutcomeStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code kafka} plugin's discovery half, driven through {@link KafkaApi} — the seam the fixture
 * is recorded at, and the same one {@code nodqora-app} substitutes.
 */
class KafkaDiscoveryTest {

    private static final KafkaConfig.Links LINKS =
            new KafkaConfig.Links(null, null, "https://grafana.acme.io/d/kafka-topic?var-topic={name}");

    @Test
    void the_prefix_admits_the_pipeline_topics_and_the_ignore_removes_connects_own() {
        DiscoveryResult result = discover(config(List.of("payments."), List.of("connect-offsets")));

        // ADR-0037. `orders.events.v1` is another team's, removed by the prefix. `connect-offsets`
        // matches nothing the prefix would admit anyway — but `listInternal=false` does not hide it,
        // because Connect's `connect-*` topics are ordinary topics as far as Kafka is concerned, and
        // the exact-name `ignore` is what removes them on a cluster whose prefix is broader.
        assertThat(result.nodes().stream().map(DiscoveredNode::key))
                .containsExactly("payments.events.enriched.v1", "payments.events.raw.v1");
        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.COMPLETE);
    }

    @Test
    void the_ignore_list_is_exact_and_subtracts_from_a_broader_prefix() {
        // The `ignore` list earns its place only when a prefix is broad enough to admit the thing.
        // `connect-` here is exactly that case, and it is the shape ADR-0037 had in mind: subtractive
        // removal, one exact name at a time, because the wrong-extras are countable.
        DiscoveryResult withIgnore = discover(config(List.of("connect-"), List.of("connect-offsets")));
        DiscoveryResult without = discover(config(List.of("connect-"), List.of()));

        assertThat(withIgnore.nodes()).isEmpty();
        assertThat(without.nodes().stream().map(DiscoveredNode::key)).containsExactly("connect-offsets");
    }

    @Test
    void a_topic_carries_exactly_one_backing_and_it_is_the_topic_itself() {
        DiscoveredNode raw = node(discover(config(List.of("payments."), List.of())), "payments.events.raw.v1");

        // ADR-0040: the one node whose identity this plugin cannot get wrong, because a topic *is*
        // its own logical key. It emits no `consumer-group` backing anywhere — those come from the
        // Kubernetes annotation, from YAML, or from `connect`'s own config, exactly as ADR-0022 says.
        assertThat(raw.backings()).containsExactly(new Backing("kafka", "topic", "payments.events.raw.v1"));
    }

    @Test
    void group_backings_are_never_discovered_onto_a_topic() {
        DiscoveryResult result = discover(config(List.of("payments."), List.of()));

        // ADR-0040, and the reason is ADR-0003's wall rather than tidiness: the set of groups on a
        // topic would be discovered on the *slow* loop, so a group appearing or vanishing would move
        // `updatedAt`, which is reserved for "architecture changed". Committed offsets are dropped
        // after `offsets.retention.minutes` — seven days by default — so a monthly batch consumer
        // would be a monthly topology edit under that model.
        assertThat(result.nodes())
                .allSatisfy(node -> assertThat(node.backings())
                        .noneMatch(backing -> backing.kind().equals("consumer-group")));
    }

    @Test
    void the_metadata_allow_list_is_four_keys_enumerated_by_name() {
        DiscoveredNode enriched =
                node(discover(config(List.of("payments."), List.of())), "payments.events.enriched.v1");

        // ADR-0038. `cleanupPolicy` earns its place for a reason the others do not: compacted versus
        // deleted is a *topological* distinction, because a compacted topic is a table rather than a
        // stream. No throughput and no topic size — the first is a sampled derivative, which is the
        // diffing ADR-0012 forbids, and the second needs `describeLogDirs`, which cannot be scoped.
        assertThat(enriched.metadata())
                .containsOnly(
                        Map.entry("partitions", 12),
                        Map.entry("replicationFactor", 3),
                        Map.entry("retentionMs", 2592000000L),
                        Map.entry("cleanupPolicy", "delete"));
    }

    @Test
    void display_name_and_owner_are_null_rather_than_the_key_repeated() {
        DiscoveredNode raw = node(discover(config(List.of("payments."), List.of())), "payments.events.raw.v1");

        // ADR-0038, following ADR-0034: a topic's display name would be byte-identical to its key,
        // and Kafka has nowhere to record an owner. Emitting either would manufacture a merge
        // conflict with YAML rather than resolve one — and would make "nobody has named this" and
        // "somebody named it after itself" indistinguishable.
        assertThat(raw.displayName()).isNull();
        assertThat(raw.ownerKey()).isNull();
        assertThat(raw.type()).isEqualTo("kafka-topic");
    }

    @Test
    void the_plugin_registers_its_own_descriptor_for_a_type_it_owns() {
        DiscoveryResult result = discover(config(List.of("payments."), List.of()));

        // ADR-0001: registered by plugins and by the YAML topology alike. ADR-0091 had `kubernetes`
        // guess nothing because its type comes from an annotation it does not understand; here the
        // type is this plugin's own constant, so there is nothing to guess.
        assertThat(result.descriptors()).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.type()).isEqualTo("kafka-topic");
            assertThat(descriptor.source()).isEqualTo("kafka");
        });
    }

    @Test
    void one_configured_template_yields_one_link() {
        DiscoveredNode raw = node(discover(config(List.of("payments."), List.of())), "payments.events.raw.v1");
        DiscoveredNode unlinked = node(
                discover(new KafkaConfig(
                        "kafka-prod.internal:9092",
                        null,
                        new KafkaConfig.Topics(List.of("payments."), List.of()),
                        new KafkaConfig.Lag(10_000L, Map.of()),
                        null)),
                "payments.events.raw.v1");

        // ADR-0039 with ADR-0032's rule carried over: no template configured ⇒ no link, never a
        // half-composed URL. §9 gives a topic exactly one link, so the fixture configures one.
        assertThat(raw.links())
                .containsExactly(new Link(
                        "dashboard",
                        "Grafana",
                        "https://grafana.acme.io/d/kafka-topic?var-topic=payments.events.raw.v1"));
        assertThat(unlinked.links()).isEmpty();
    }

    @Test
    void a_prefix_matching_nothing_is_a_partial_that_names_it() {
        DiscoveryResult result = discover(config(List.of("payments.", "billing."), List.of()));

        // ADR-0042's first named reason, and ADR-0047's zero-output guard in the same line: a
        // declared prefix is a declared expectation, so zero matches is either an ACL failure or a
        // wrong prefix. The ambiguity cannot be removed, so it shouts rather than shrugs.
        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.PARTIAL);
        assertThat(result.outcome().reasons()).containsExactly("topic include prefix 'billing.' matched no topic");
        assertThat(result.nodes()).hasSize(2);
    }

    @Test
    void a_topic_whose_configs_came_back_empty_names_the_missing_acl() {
        DiscoveryResult result = new KafkaPlugin(new KafkaApi() {
                    @Override
                    public List<String> listTopics(KafkaConfig config) {
                        return List.of("payments.events.raw.v1");
                    }

                    @Override
                    public List<ObservedTopic> describeTopics(KafkaConfig config, List<String> names) {
                        return List.of(new ObservedTopic("payments.events.raw.v1", 12, 3, Map.of()));
                    }

                    @Override
                    public List<ObservedGroup> consumerGroupOffsets(KafkaConfig config, List<String> groupIds) {
                        return List.of();
                    }
                })
                .discover(new DiscoveryRequest<>("production", config(List.of("payments."), List.of())));

        // ADR-0042's second named reason, and unlike the first it is unambiguous: we have the topic,
        // so we are authorized to see it, and empty configs can only mean the second, distinct
        // `DescribeConfigs` ACL is missing. This is why `ObservedTopic` keeps the raw config map —
        // folding it into nullable fields would erase the difference between "no ACL" and "no
        // retention override".
        assertThat(result.outcome().reasons())
                .containsExactly("topic payments.events.raw.v1 returned no configs; the DescribeConfigs ACL is missing");
        assertThat(result.nodes()).hasSize(1);
    }

    @Test
    void an_unreachable_cluster_is_failed_and_carries_no_entries() {
        DiscoveryResult result = new KafkaPlugin(unreachable())
                .discover(new DiscoveryRequest<>("production", config(List.of("payments."), List.of())));

        // ADR-0046: a FAILED snapshot changes nothing in the store, so the topics go visibly stale
        // rather than being deleted by a poll that never saw them.
        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.FAILED);
        assertThat(result.nodes()).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private static KafkaConfig config(List<String> include, List<String> ignore) {
        return new KafkaConfig(
                "kafka-prod.internal:9092",
                null,
                new KafkaConfig.Topics(include, ignore),
                new KafkaConfig.Lag(10_000L, Map.of()),
                LINKS);
    }

    private static DiscoveryResult discover(KafkaConfig config) {
        return new KafkaPlugin(new RecordedKafkaApi()).discover(new DiscoveryRequest<>("production", config));
    }

    private static KafkaApi unreachable() {
        return new KafkaApi() {
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
        };
    }

    private static DiscoveredNode node(DiscoveryResult result, String key) {
        return result.nodes().stream()
                .filter(node -> node.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node keyed " + key));
    }
}
