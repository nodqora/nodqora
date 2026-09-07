// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kafka;

import java.util.List;

/**
 * The plugin's <b>outbound-client interface</b> — the one seam ADR-0099 records at, in the same
 * shape as {@code KubernetesApi} and {@code ConnectApi}.
 *
 * <p>Three methods, and the split between them is ADR-0037's scope rule made structural rather than
 * incidental. {@link #listTopics} is the only unscoped call in the plugin and it returns
 * <em>names</em>; the prefix filter then runs <b>above</b> this seam, so
 * {@link #describeTopics(KafkaConfig, List)} is asked only about topics that are in scope. A seam
 * that took the config and returned "the topics you care about" would hide the one rule most worth
 * testing below the recording.
 *
 * <p><b>{@code listGroups} is deliberately not here, and never will be.</b> ADR-0040 has the plugin
 * read exactly the <em>routed</em> group set — the union of {@code consumer-group} backings other
 * plugins stamped — because there is no API answering "which groups consume topic X". The
 * alternative is {@code listGroups()} across every broker followed by committed offsets for every
 * group on the cluster, every thirty seconds, to serve a prefix-scoped handful of topics: the exact
 * inverse of ADR-0037, and unnarrowable, since group names bear no relation to topic prefixes.
 *
 * <p>Config arrives as an argument rather than being held, because a plugin and everything under it
 * is a stateless thread-safe singleton (ADR-0012).
 */
public interface KafkaApi {

    /** {@code listTopics(listInternal=false)}. The one call whose scope cannot be pushed down. */
    List<String> listTopics(KafkaConfig config);

    /** {@code describeTopics} + {@code describeConfigs}, over the in-scope names only. */
    List<ObservedTopic> describeTopics(KafkaConfig config, List<String> names);

    /**
     * {@code listConsumerGroupOffsets} joined against {@code listOffsets(latest)}, over the routed
     * group set only.
     *
     * <p>The group→topic join falls out for free and is why this is one call rather than two
     * questions: {@code listConsumerGroupOffsets} returns a {@code Map<TopicPartition, …>}, so the
     * same response that yields a group's lag also names the topics that lag is <em>on</em>. That
     * single response therefore produces both the service-node routing and the topic-node join.
     */
    List<ObservedGroup> consumerGroupOffsets(KafkaConfig config, List<String> groupIds);
}
