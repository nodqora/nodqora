// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kafka;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.springframework.stereotype.Component;

/**
 * The only place a real broker is spoken to (ADR-0099). No test reaches it: everything above
 * {@link KafkaApi} runs against the recording, and the accepted cost is ADR-0099's — <b>nothing here
 * proves the product can talk to a real cluster</b>.
 *
 * <p>An {@code Admin} is opened per call rather than held, because a plugin is a stateless
 * thread-safe singleton whose per-run state arrives as arguments (ADR-0012), and because the config
 * — bootstrap address, SASL properties — belongs to the environment being polled rather than to this
 * object. Two environments' polls run in parallel on virtual threads, and a held client would have to
 * be one per environment, which is state.
 *
 * <p><b>{@code listGroups} does not appear in this file and neither does {@code describeLogDirs}.</b>
 * The first is ADR-0040's decision — the routed group set is the whole scope — and the second is
 * ADR-0038's, because the public API always sends {@code setTopics(null)} and so cannot be scoped at
 * all, which would make it the single most expensive call in either API against the whole cluster
 * every slow poll.
 */
@Component
class AdminClientKafkaApi implements KafkaApi {

    @Override
    public List<String> listTopics(KafkaConfig config) {
        try (Admin admin = admin(config)) {
            // ADR-0037: `listInternal=false` hides `__consumer_offsets` and `__transaction_state`,
            // and is not sufficient on its own — Connect's `connect-*` topics are ordinary topics as
            // far as Kafka is concerned, and the config's `ignore` list is what removes those.
            return new ArrayList<>(
                    get(admin.listTopics(new ListTopicsOptions().listInternal(false)).names(), "listTopics"));
        }
    }

    @Override
    public List<ObservedTopic> describeTopics(KafkaConfig config, List<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        try (Admin admin = admin(config)) {
            Map<String, TopicDescription> described =
                    get(admin.describeTopics(names).allTopicNames(), "describeTopics");

            // One batched request for every in-scope topic, which is why ADR-0042 could reject
            // research #5's three-tier schedule: the prefix scope keeps this response small.
            List<ConfigResource> resources = names.stream()
                    .map(name -> new ConfigResource(ConfigResource.Type.TOPIC, name))
                    .toList();
            Map<ConfigResource, Config> configs = get(admin.describeConfigs(resources).all(), "describeConfigs");

            List<ObservedTopic> topics = new ArrayList<>(names.size());
            described.forEach((name, description) -> {
                Config topicConfig = configs.get(new ConfigResource(ConfigResource.Type.TOPIC, name));
                Map<String, String> values = new LinkedHashMap<>();
                if (topicConfig != null) {
                    for (ConfigEntry entry : topicConfig.entries()) {
                        values.put(entry.name(), entry.value());
                    }
                }
                topics.add(new ObservedTopic(
                        name,
                        description.partitions().size(),
                        description.partitions().stream()
                                .mapToInt(partition -> partition.replicas().size())
                                .max()
                                .orElse(0),
                        values));
            });
            return topics;
        }
    }

    @Override
    public List<ObservedGroup> consumerGroupOffsets(KafkaConfig config, List<String> groupIds) {
        if (groupIds.isEmpty()) {
            return List.of();
        }
        try (Admin admin = admin(config)) {
            List<ObservedGroup> groups = new ArrayList<>(groupIds.size());
            Map<String, Map<TopicPartition, OffsetAndMetadata>> committed = new LinkedHashMap<>();
            Map<TopicPartition, OffsetSpec> watermarks = new HashMap<>();

            for (String groupId : groupIds) {
                Map<TopicPartition, OffsetAndMetadata> offsets = get(
                        admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata(),
                        "listConsumerGroupOffsets(" + groupId + ")");
                committed.put(groupId, offsets);
                offsets.keySet().forEach(partition -> watermarks.put(partition, OffsetSpec.latest()));
            }

            // One `listOffsets` for every partition any routed group commits on, batched per leader
            // by the client itself. This is the call that turns a committed offset into a lag.
            var latest = watermarks.isEmpty()
                    ? Map.<TopicPartition, org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo>of()
                    : get(admin.listOffsets(watermarks).all(), "listOffsets");

            committed.forEach((groupId, offsets) -> {
                List<PartitionOffsets> partitions = new ArrayList<>(offsets.size());
                offsets.forEach((partition, offset) -> {
                    var end = latest.get(partition);
                    partitions.add(new PartitionOffsets(
                            partition.topic(),
                            partition.partition(),
                            offset == null ? null : offset.offset(),
                            end == null ? null : end.offset()));
                });
                groups.add(new ObservedGroup(groupId, partitions));
            });
            return groups;
        }
    }

    /**
     * ADR-0042: {@code properties} is an untyped passthrough because Kafka security configuration is
     * open-ended, and allow-listing governs what <em>leaves</em> the plugin rather than what enters
     * it. The bootstrap address is set after it, so a stray {@code bootstrap.servers} in the
     * passthrough cannot quietly point the poll at another cluster.
     */
    private static Admin admin(KafkaConfig config) {
        Properties properties = new Properties();
        properties.putAll(config.properties());
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrap());
        return Admin.create(properties);
    }

    private static <T> T get(org.apache.kafka.common.KafkaFuture<T> future, String what) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaApiException(what + " was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new KafkaApiException("%s failed: %s".formatted(what, cause.getMessage()), cause);
        }
    }
}
