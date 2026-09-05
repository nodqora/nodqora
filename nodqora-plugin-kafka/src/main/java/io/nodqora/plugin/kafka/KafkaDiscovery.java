package io.nodqora.plugin.kafka;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.DiscoveredNode;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.Link;
import io.nodqora.plugin.api.Outcome;
import io.nodqora.plugin.api.TypeDescriptor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One environment's Kafka snapshot: list the cluster's topics, keep the ones the prefix scope
 * admits, describe those, and emit one node each.
 *
 * <p><b>{@code kafka} emits exactly one backing per node</b> (ADR-0040):
 * {@code {plugin: kafka, kind: topic, reference: <topic name>}} on the node whose key <em>is</em>
 * that topic's name — the one node whose identity it cannot get wrong, because a topic is its own
 * logical key. It emits no {@code consumer-group} backings anywhere; those come from the Kubernetes
 * annotation, from YAML, or from {@code connect}'s own config, exactly as ADR-0022 says.
 *
 * <p>Four things it deliberately does <em>not</em> do:
 *
 * <ul>
 *   <li><b>Emit any node but a topic</b> (ADR-0036). A broker is physical and would sit disconnected
 *       from every pipeline; a cluster is already an Environment property; and a <b>consumer group is
 *       a backing, never a node</b> — {@code enrich-consumer-prod} and {@code payments-enricher} are
 *       one thing, and promoting the group would put both on the canvas, which is the duplicate
 *       ADR-0020's flat key space exists to prevent.
 *   <li><b>Emit any edge.</b> A topic's producers and consumers are the other plugins' to name; this
 *       one reads a topic's own configuration and nothing about who talks to it.
 *   <li><b>Discover group backings onto topic nodes</b> (ADR-0040). The set of groups on a topic
 *       would then be discovered on the slow topology loop, so a group appearing or vanishing would
 *       move {@code updatedAt}, which ADR-0003 reserves for <em>architecture changed</em>. Committed
 *       offsets are dropped after {@code offsets.retention.minutes} — seven days by default — so a
 *       monthly batch consumer's group would be a monthly topology edit under that model, versus a
 *       lag number that stops and starts under this one.
 *   <li><b>Report throughput or topic size</b> (ADR-0038). Throughput exists only as a sampled
 *       derivative of two {@code listOffsets} calls, and sampling <em>is</em> the diffing ADR-0012
 *       forbids; {@code describeLogDirs} always sends {@code setTopics(null)} and so cannot be scoped
 *       at all, which is precisely the guarantee ADR-0037 exists to give.
 * </ul>
 */
class KafkaDiscovery {

    static final String PLUGIN_ID = "kafka";

    /** ADR-0038: {@code key} is the topic name and {@code type} is this constant. */
    static final String NODE_TYPE = "kafka-topic";

    static final String TOPIC_KIND = "topic";

    private static final Logger log = LoggerFactory.getLogger(KafkaDiscovery.class);

    private final KafkaApi api;

    KafkaDiscovery(KafkaApi api) {
        this.api = api;
    }

    DiscoveryResult discover(String environmentKey, KafkaConfig config) {
        List<String> listed;
        try {
            listed = api.listTopics(config);
        } catch (RuntimeException e) {
            // ADR-0046: a FAILED snapshot carries no entries and changes nothing in the store, so
            // the topics go visibly stale rather than being deleted by a poll that never saw them.
            return DiscoveryResult.failed("listTopics failed: " + message(e));
        }

        List<String> inScope = listed.stream()
                .filter(topic -> config.topics().admits(topic))
                .sorted(Comparator.comparing(KafkaDiscovery::folded))
                .toList();

        List<ObservedTopic> described;
        try {
            described = api.describeTopics(config, inScope);
        } catch (RuntimeException e) {
            return DiscoveryResult.failed("describeTopics failed: " + message(e));
        }

        List<String> reasons = new ArrayList<>();

        // ADR-0042's first named PARTIAL reason, and ADR-0047's zero-output guard in the same line:
        // a declared prefix is a declared expectation, so zero matches is either an ACL failure or a
        // wrong prefix — an ambiguity that cannot be removed, so it shouts rather than shrugs. Only
        // the plugin can tell an empty scope from an empty result, and a COMPLETE empty snapshot
        // would delete every topic in the environment.
        for (String prefix : config.topics().include()) {
            if (listed.stream().noneMatch(topic -> config.topics().matches(topic, prefix))) {
                reasons.add("topic include prefix '%s' matched no topic".formatted(prefix));
            }
        }

        // ADR-0042's second, and unlike the first it is unambiguous: we have the topic, so we are
        // authorized to see it, and empty configs can only mean the second, distinct
        // `DescribeConfigs` ACL is missing.
        described.stream()
                .filter(topic -> topic.configs().isEmpty())
                .forEach(topic -> reasons.add(
                        "topic %s returned no configs; the DescribeConfigs ACL is missing".formatted(topic.name())));

        KafkaLinks links = new KafkaLinks(config.links());
        List<DiscoveredNode> nodes = described.stream()
                .sorted(Comparator.comparing(topic -> folded(topic.name())))
                .map(topic -> node(topic, links))
                .toList();

        log.info(
                "discovery {}/{}: {} topic(s) of {} listed, {} in scope",
                environmentKey,
                PLUGIN_ID,
                nodes.size(),
                listed.size(),
                inScope.size());

        return new DiscoveryResult(
                nodes,
                List.of(),
                List.of(),
                // ADR-0001: registered by plugins and by the YAML topology alike. Unlike ADR-0091's
                // case this is not a guess — the type is this plugin's own constant.
                List.of(new TypeDescriptor(NODE_TYPE, "Kafka Topic", "messaging", "stream", PLUGIN_ID)),
                reasons.isEmpty() ? Outcome.complete() : Outcome.partial(reasons));
    }

    /**
     * ADR-0038's allow-list, enumerated by name: {@code partitions} and {@code replicationFactor}
     * from {@code describeTopics}, {@code retentionMs} and {@code cleanupPolicy} from
     * {@code describeConfigs}.
     *
     * <p>{@code cleanupPolicy} earns its place for a reason the others do not: compacted versus
     * deleted is a <em>topological</em> distinction, because a compacted topic is a table rather than
     * a stream.
     *
     * <p>{@code displayName} and {@code ownerKey} are emitted {@code null}, following ADR-0034 — not
     * to resolve a merge conflict with YAML but to avoid manufacturing one. A topic's display name
     * would be byte-identical to its key, and Kafka has nowhere to record an owner.
     */
    private static DiscoveredNode node(ObservedTopic topic, KafkaLinks links) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        put(metadata, "partitions", topic.partitions());
        put(metadata, "replicationFactor", topic.replicationFactor());
        put(metadata, "retentionMs", number(topic.config(ObservedTopic.RETENTION_MS)));
        put(metadata, "cleanupPolicy", topic.config(ObservedTopic.CLEANUP_POLICY));

        List<Link> composed = links.of(topic.name());
        return new DiscoveredNode(
                topic.name().trim(),
                NODE_TYPE,
                null,
                null,
                null,
                composed,
                List.of(new Backing(PLUGIN_ID, TOPIC_KIND, topic.name().trim())),
                metadata);
    }

    private static void put(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    /** {@code retention.ms} is a string on the wire and a duration to a reader; keep it a number. */
    private static Object number(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // `-1` and plain integers are what Kafka returns; anything else is left verbatim rather
            // than dropped, because a value we cannot parse is still a value the operator set.
            return value;
        }
    }

    /** ADR-0020: keys are compared case-folded, so this plugin's own ordering folds too. */
    private static String folded(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
