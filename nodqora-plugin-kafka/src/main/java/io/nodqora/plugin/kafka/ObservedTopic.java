package io.nodqora.plugin.kafka;

import java.util.Map;
import java.util.Objects;

/**
 * One in-scope topic, as {@code describeTopics} and {@code describeConfigs} together see it.
 *
 * <p>{@code configs} is the raw {@code describeConfigs} response and stays raw on purpose:
 * <b>empty is load-bearing</b>. ADR-0042's second named {@code PARTIAL} reason is a topic that
 * {@code listTopics} returned whose configs came back empty — unambiguous, because having the topic
 * proves we may see it, so an empty config can only be the missing {@code DescribeConfigs} ACL.
 * Folding {@code retention.ms} into a nullable field on this record would destroy the distinction
 * between "no ACL" and "the topic genuinely has no retention override".
 *
 * <p>What leaves the plugin is allow-listed by name in {@link KafkaDiscovery}, not taken from here
 * wholesale (ADR-0038).
 */
public record ObservedTopic(String name, Integer partitions, Integer replicationFactor, Map<String, String> configs) {

    static final String RETENTION_MS = "retention.ms";
    static final String CLEANUP_POLICY = "cleanup.policy";

    public ObservedTopic {
        Objects.requireNonNull(name, "name");
        configs = configs == null ? Map.of() : Map.copyOf(configs);
    }

    public String config(String key) {
        String value = configs.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
