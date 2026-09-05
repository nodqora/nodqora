package io.nodqora.plugin.kafka;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.Health;
import io.nodqora.plugin.api.HealthCapability.HealthResult;
import io.nodqora.plugin.api.HealthCapability.ObservableNode;
import io.nodqora.plugin.api.Outcome;
import io.nodqora.plugin.api.StateContribution;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One environment's Kafka observation, and <b>the plugin that reads every signal and attributes
 * none of them</b> (ADR-0022).
 *
 * <p>The routed group set is assembled from the {@code consumer-group} backings on the nodes the
 * engine handed over — stamped there by {@code kubernetes} from an annotation, by {@code yaml} from
 * a stanza, and by {@code connect} from its own config. This plugin never enumerates groups and
 * <b>{@code listGroups} is never called</b> (ADR-0040): it reads lag for the groups it is routed to,
 * and never decides ownership.
 *
 * <p><b>The group→topic join then falls out for free.</b> {@code listConsumerGroupOffsets} returns a
 * map keyed by topic-partition, so the same response that yields a group's lag also names the topics
 * that lag is <em>on</em>. One call over a small declared set produces both the service-node routing
 * and the topic-node join — which is the whole reason topic health is affordable at thirty seconds.
 *
 * <p>Two things it deliberately does <em>not</em> do:
 *
 * <ul>
 *   <li><b>Emit {@code DISABLED}, ever</b> (ADR-0029). An {@code EMPTY} group is the exact state of a
 *       deliberately scaled-down consumer and of a crashed one, and {@code DISABLED} requires
 *       evidence of intent. {@code kubernetes} may say it because {@code spec.replicas: 0} is
 *       declarative; nothing here is.
 *   <li><b>Emit {@code UNHEALTHY}, ever</b> (ADR-0025). Not at 2,100,000 and growing. <em>Lag means
 *       behind, not broken</em>: a consumer that is behind is still doing its job, and broken-ness
 *       arrives from the workload crash-looping or the tasks failing, which are other plugins'
 *       signals. One threshold, not a pair.
 * </ul>
 */
class KafkaHealth {

    private static final Logger log = LoggerFactory.getLogger(KafkaHealth.class);

    /** ADR-0028's allow-list for this plugin: one key, enumerated by name. */
    private static final String MAX_CONSUMER_LAG = "maxConsumerLag";

    private static final String CONSUMER_GROUP_KIND = "consumer-group";

    private final KafkaApi api;

    KafkaHealth(KafkaApi api) {
        this.api = api;
    }

    HealthResult observe(String environmentKey, List<ObservableNode> nodes, KafkaConfig config) {
        // The routed set, in one pass over the nodes the engine handed over. Deduplicated and
        // ordered, because two nodes may legitimately name one group and the call must not ask twice.
        SequencedSet<String> routed = new LinkedHashSet<>();
        nodes.forEach(node -> node.backings().stream()
                .filter(backing -> KafkaDiscovery.PLUGIN_ID.equals(backing.plugin()))
                .filter(backing -> CONSUMER_GROUP_KIND.equals(backing.kind()))
                .map(Backing::reference)
                .forEach(routed::add));

        if (routed.isEmpty()) {
            // Nothing routed a group, so there is no lag to read and no round trip worth spending.
            // Every node handed over is a topic with no routed group, which ADR-0040 makes UNKNOWN
            // rather than HEALTHY: zero groups is zero signal, and reporting HEALTHY from no signal
            // is what ADR-0026 split `outcome` from `health` to prevent.
            return new HealthResult(Map.of(), Outcome.complete());
        }

        List<ObservedGroup> observed;
        try {
            observed = api.consumerGroupOffsets(config, List.copyOf(routed));
        } catch (RuntimeException e) {
            // ADR-0046: FAILED leaves the store untouched, so the last good lag stands and goes
            // visibly stale rather than every topic flipping grey because one poll blinked.
            return new HealthResult(Map.of(), Outcome.failed("consumer group offsets failed: " + message(e)));
        }

        Verdicts verdicts = Verdicts.of(observed, config);

        Map<String, StateContribution> contributions = new LinkedHashMap<>();
        for (ObservableNode node : nodes) {
            contribution(node, verdicts).ifPresent(state -> contributions.put(node.key(), state));
        }

        log.debug(
                "health {}/{}: {} node(s) routed over {} group(s), {} observed",
                environmentKey,
                KafkaDiscovery.PLUGIN_ID,
                nodes.size(),
                routed.size(),
                contributions.size());

        // A group that was routed but did not come back is not reported as a reason. Committed
        // offsets are dropped after `offsets.retention.minutes` — seven days by default — so a group
        // that has simply been idle is indistinguishable from one that never existed, and a PARTIAL
        // there would fire on an ordinary quiet weekend.
        return new HealthResult(contributions, Outcome.complete());
    }

    /**
     * A node's verdict is the collapse of the per-group verdicts that reach it, by whichever of the
     * two routes it carries — and a node may carry both.
     *
     * <ul>
     *   <li>a {@code consumer-group} backing another plugin stamped, which is how a <em>service</em>
     *       or a <em>connector</em> gets a lag verdict at all;
     *   <li>a {@code topic} backing this plugin stamped, joined to every group committing offsets on
     *       that topic — <b>the one place {@code kafka} can attribute without help</b>, because
     *       committed offsets name {@code (group, topic-partition)} directly. ADR-0022 is untouched.
     * </ul>
     *
     * <p>ADR-0024's collapse runs here, inside the plugin, over the per-group healths — and
     * <b>never over the lag numbers</b>. Collapsing lag first would let the group with the highest
     * lag borrow another group's threshold.
     */
    private static Optional<StateContribution> contribution(ObservableNode node, Verdicts verdicts) {
        SequencedSet<Verdict> reaching = new LinkedHashSet<>();
        for (Backing backing : node.backings()) {
            if (!KafkaDiscovery.PLUGIN_ID.equals(backing.plugin())) {
                continue;
            }
            if (CONSUMER_GROUP_KIND.equals(backing.kind())) {
                reaching.addAll(verdicts.byGroup(backing.reference()));
            } else if (KafkaDiscovery.TOPIC_KIND.equals(backing.kind())) {
                reaching.addAll(verdicts.onTopic(backing.reference()));
            }
        }

        List<Verdict> ordered =
                reaching.stream().sorted(Comparator.comparing(Verdict::groupId)).toList();
        Health collapsed = Health.collapse(ordered.stream().map(Verdict::health).toList());
        if (collapsed == Health.UNKNOWN) {
            // An abstention is an omission (ADR-0104). A topic with no routed group, and a group that
            // came back with nothing committed, both take this path.
            return Optional.empty();
        }
        return Optional.of(new StateContribution(collapsed, rawSignal(ordered), metrics(ordered)));
    }

    /**
     * ADR-0028: {@code "lag 40000"} — a short line and a <em>gist</em>. The core joins one of these
     * per plugin in registry order, so CONTEXT.md's own worked example
     * {@code "3 desired / 2 ready; lag 40000"} is literally {@code kubernetes} then this.
     *
     * <p>A node several groups reach names how many rather than listing them: {@code enriched.v1} is
     * read by both sinks, and the number a reader needs is the worst one and the fact that it is a
     * maximum over more than one.
     */
    private static String rawSignal(List<Verdict> verdicts) {
        if (verdicts.isEmpty()) {
            return null;
        }
        Verdict worst = verdicts.stream()
                .max(Comparator.comparingLong(Verdict::maxLag))
                .orElseThrow();
        // Distinct groups rather than verdicts, because a verdict is now per (group, topic): a node
        // backed by two topics one group reads collects two of them, and the sentence says groups.
        long groups = verdicts.stream().map(Verdict::groupId).distinct().count();
        return groups == 1
                ? "lag %d".formatted(worst.maxLag())
                : "max lag %d over %d groups".formatted(worst.maxLag(), groups);
    }

    /**
     * ADR-0028's allow-list: {@code maxConsumerLag}, singular, and <b>max rather than sum</b> — a
     * summed lag over two groups is a number describing nothing that exists.
     */
    private static Map<String, Object> metrics(List<Verdict> verdicts) {
        if (verdicts.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> metrics = new TreeMap<>();
        metrics.put(MAX_CONSUMER_LAG, verdicts.stream().mapToLong(Verdict::maxLag).max().orElse(0L));
        return metrics;
    }

    /**
     * One group's answer: the max lag over its partitions, evaluated against <em>its own</em>
     * threshold.
     *
     * <p>The order of those two steps is ADR-0025's, and it is the whole of the arithmetic:
     * <b>max lag over partitions within a group → that group's threshold → per-group health →
     * ADR-0024 over the healths.</b>
     */
    private record Verdict(String groupId, long maxLag, Health health) {}

    /**
     * The two indexes one response produces: group → verdict, and topic → the verdicts on it.
     *
     * <p><b>They are scoped differently on purpose, and that is the whole of this class.</b> A
     * group's verdict is the max over <em>all</em> its partitions, because that is what a service
     * routed by a {@code consumer-group} backing is behind by. A topic's verdict is the max over
     * that group's partitions <em>on that topic</em>, because that is what the topic is behind by.
     * The threshold is the same one either way — ADR-0025 keys it by group, and a topic borrows the
     * threshold of whoever reads it.
     *
     * <p>One number for both was wrong, and invisible until a group read two topics: every topic a
     * group consumed reported the group's worst lag wherever it was, so a single busy topic turned
     * the group's quiet ones amber for a backlog they did not have. The reference pipeline has one
     * topic per group throughout, which is why the fixtures agree with either reading.
     */
    private record Verdicts(Map<String, Verdict> byGroup, Map<String, List<Verdict>> byTopic) {

        static Verdicts of(List<ObservedGroup> observed, KafkaConfig config) {
            Map<String, Verdict> byGroup = new LinkedHashMap<>();
            Map<String, List<Verdict>> byTopic = new LinkedHashMap<>();
            for (ObservedGroup group : observed) {
                // Partitions with no committed offset are skipped, never counted as zero: a group
                // that has not started is not a group that is caught up (ADR-0025).
                List<PartitionOffsets> committed = group.partitions().stream()
                        .filter(partition -> partition.lag() != null)
                        .toList();
                if (committed.isEmpty()) {
                    continue;
                }
                long threshold = config.lag().thresholdFor(group.groupId());
                byGroup.put(folded(group.groupId()), verdict(group.groupId(), committed, threshold));

                // `listConsumerGroupOffsets` keys by topic-partition, so the per-topic split costs
                // a grouping over a list already in hand — no second round trip.
                committed.stream()
                        .collect(Collectors.groupingBy(
                                PartitionOffsets::topic, LinkedHashMap::new, Collectors.toList()))
                        .forEach((topic, partitions) -> byTopic
                                .computeIfAbsent(folded(topic), ignored -> new ArrayList<>())
                                .add(verdict(group.groupId(), partitions, threshold)));
            }
            return new Verdicts(byGroup, byTopic);
        }

        /**
         * ADR-0025's arithmetic over whatever slice of a group's partitions it is handed: <b>max lag
         * over the partitions → the group's threshold → one health</b>. Applied to all of a group's
         * partitions it answers for the group; applied to one topic's, for the topic.
         */
        private static Verdict verdict(String groupId, List<PartitionOffsets> partitions, long threshold) {
            long maxLag = partitions.stream()
                    .mapToLong(partition -> partition.lag())
                    .max()
                    .orElse(0L);
            return new Verdict(groupId, maxLag, maxLag >= threshold ? Health.DEGRADED : Health.HEALTHY);
        }

        List<Verdict> byGroup(String groupId) {
            Verdict verdict = byGroup.get(folded(groupId));
            return verdict == null ? List.of() : List.of(verdict);
        }

        List<Verdict> onTopic(String topic) {
            return byTopic.getOrDefault(folded(topic), List.of());
        }
    }

    private static String folded(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
