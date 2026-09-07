// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.connect;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.DiscoveredEdge;
import io.nodqora.plugin.api.DiscoveredNode;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.Outcome;
import io.nodqora.plugin.api.TypeDescriptor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One environment's Connect snapshot: one {@code GET /connectors?expand=info}, subtract what the
 * scope does not admit, and emit one node per connector.
 *
 * <p><b>Identity is free here</b>, which is the whole reason ADR-0021's cascade names only
 * {@code kubernetes} as having a problem: a connector <em>is</em> its own logical key, so this
 * plugin resolves nothing and cannot get a key wrong.
 *
 * <p>Three things it deliberately does <em>not</em> do:
 *
 * <ul>
 *   <li><b>Emit a destination edge</b> (ADR-0041). It parses the {@code topics} key and nothing
 *       else, because {@code topics} is Connect's own configuration vocabulary while
 *       {@code iceberg.tables} and {@code connection.url} are a third party's. §11.4 of the product
 *       plan uses {@code payments-events-v1} as its worked example and it is the case that does not
 *       work: the index is not in the ES sink's config at all — {@code connection.url} is the
 *       cluster endpoint — so inferring a destination per connector class would emit an edge to the
 *       <em>topic's</em> own key under ADR-0020, a self-loop merging an index into a topic.
 *       {@code iceberg.tables} is the contrasting case: the node key, stated verbatim, and still not
 *       used, because reading it needs a per-class registry whose absence the user cannot tell from
 *       a misconfigured connector.
 *   <li><b>Forward the connector config.</b> ADR-0038 allow-lists three keys by name on the way out.
 *       {@code GET /connectors?expand=info} returns inlined secrets unmasked, so "everything except
 *       {@code *.password}" is the wrong direction: the map never leaves this class.
 *   <li><b>Write anything onto a destination node.</b> Not a link, not a type, not an owner
 *       (ADR-0039). An edge endpoint is not a node this plugin knows anything about.
 * </ul>
 */
class ConnectDiscovery {

    static final String PLUGIN_ID = "connect";

    /**
     * ADR-0038: one constant type for source and sink alike. Direction is already carried by the
     * edges and ADR-0001 says the core never branches on type, so splitting would buy two
     * descriptors and no behaviour.
     */
    static final String NODE_TYPE = "connect-connector";

    static final String CONNECTOR_KIND = "connector";

    /**
     * ADR-0013, ADR-0022: {@code Backing.plugin} names the object's <em>technology domain</em>, not
     * its discoverer. A Connect-generated consumer group is a Kafka object, and this is the route by
     * which {@code connect-payments-es-sink}'s lag reaches {@code payments-es-sink} at all — the
     * plugin that can read that lag is not the one that can say whose lag it is.
     */
    private static final String CONSUMER_GROUP_DOMAIN = "kafka";

    private static final String CONSUMER_GROUP_KIND = "consumer-group";

    /** Connect's own vocabulary, and the only four keys read out of a connector's config. */
    private static final String TOPICS = "topics";

    private static final String TOPICS_REGEX = "topics.regex";
    private static final String CONNECTOR_CLASS = "connector.class";
    private static final String TASKS_MAX = "tasks.max";

    /**
     * ADR-0022: {@code connect} emits its own group, from its config, "which is authoritative
     * including when {@code consumer.override.group.id} overrides the default". Connect's default
     * for a sink is {@code connect-<connector name>}, which is exactly the fixture's
     * {@code connect-payments-es-sink}.
     */
    private static final String GROUP_OVERRIDE = "consumer.override.group.id";

    private static final Logger log = LoggerFactory.getLogger(ConnectDiscovery.class);

    private final ConnectApi api;

    ConnectDiscovery(ConnectApi api) {
        this.api = api;
    }

    DiscoveryResult discover(String environmentKey, ConnectConfig config) {
        List<ConnectorInfo> listed;
        try {
            listed = api.connectors(config);
        } catch (RuntimeException e) {
            // ADR-0042: an error on `GET /connectors` is FAILED — the whole scope went dark. Under
            // ADR-0046 that leaves the store untouched, so the connectors go visibly stale rather
            // than being deleted by a poll that never saw them.
            return DiscoveryResult.failed("GET /connectors failed: " + message(e));
        }

        List<String> reasons = new ArrayList<>();
        List<ConnectorInfo> admitted = new ArrayList<>();
        for (ConnectorInfo connector : listed) {
            if (!config.connectors().admits(connector.name())) {
                continue;
            }
            if (connector.error() != null) {
                // ADR-0042's other half: the listing succeeded and one entry did not. That is
                // PARTIAL naming the connector, never FAILED — the rest of the scope was read.
                reasons.add("connector %s could not be expanded: %s".formatted(connector.name(), connector.error()));
                continue;
            }
            admitted.add(connector);
        }

        // ADR-0090: a zero-match include prefix is a named PARTIAL reason, and it names *which*
        // prefix went dark. A declared prefix is a declared expectation, so zero matches is either
        // an ACL failure or a wrong prefix — both loud rather than shrugged at. This is also
        // ADR-0047's zero-output guard: only the plugin can tell an empty scope from an empty
        // result, and a COMPLETE empty snapshot would delete every connector in the environment.
        for (String prefix : config.connectors().include()) {
            if (listed.stream().noneMatch(connector -> config.connectors().matches(connector.name(), prefix))) {
                reasons.add("connector include prefix '%s' matched no connector".formatted(prefix));
            }
        }

        List<DiscoveredNode> nodes = new ArrayList<>();
        List<DiscoveredEdge> edges = new ArrayList<>();
        for (ConnectorInfo connector : admitted) {
            nodes.add(node(connector, config));
            edges.addAll(sourcesFrom(connector));
        }

        log.info(
                "discovery {}/{}: {} connector(s) of {} listed, {} SOURCES_FROM edge(s)",
                environmentKey,
                PLUGIN_ID,
                nodes.size(),
                listed.size(),
                edges.size());

        return new DiscoveryResult(
                nodes.stream().sorted(Comparator.comparing(node -> folded(node.key()))).toList(),
                edges.stream()
                        .sorted(Comparator.comparing((DiscoveredEdge edge) -> folded(edge.fromKey()))
                                .thenComparing(edge -> folded(edge.toKey())))
                        .toList(),
                List.of(),
                // ADR-0001: a descriptor is registered by plugins and by the YAML topology alike.
                // Unlike ADR-0091's case this is not a guess — the type is this plugin's own
                // constant, so it knows exactly what it is and how it should draw.
                List.of(new TypeDescriptor(NODE_TYPE, "Connect Connector", "integration", "connector", PLUGIN_ID)),
                reasons.isEmpty() ? Outcome.complete() : Outcome.partial(reasons));
    }

    /**
     * ADR-0038's payload: {@code key} is the connector name, {@code type} is the constant, and
     * {@code displayName} and {@code ownerKey} are {@code null} — following ADR-0034, not to resolve
     * a merge conflict with YAML but to avoid manufacturing one. Connect has nowhere to record an
     * owner, and a display name would be byte-identical to the key.
     *
     * <p>{@code topics} is deliberately absent from the metadata although ADR-0014 illustrated
     * allow-listing with it: it becomes edges, and storing it twice creates two places to disagree.
     */
    private DiscoveredNode node(ConnectorInfo connector, ConnectConfig config) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        put(metadata, "class", connector.config(CONNECTOR_CLASS));
        put(metadata, "type", connector.type());
        put(metadata, "tasksMax", connector.config(TASKS_MAX));

        return new DiscoveredNode(
                connector.name().trim(),
                NODE_TYPE,
                null,
                null,
                null,
                new ConnectLinks(config.links()).of(connector.name()),
                backings(connector, config),
                metadata);
    }

    /**
     * Up to three backings, and each is a different plugin's technology domain — which is ADR-0022
     * in one method: <em>whichever plugin knows the node key emits the backing</em>.
     *
     * <ol>
     *   <li>{@code connect/connector} — this plugin's own route back to the node on the fast loop.
     *   <li>the configured workload, so {@code kubernetes} is handed a node it could never have
     *       discovered: ADR-0031 suppresses the {@code kafka-connect} StatefulSet from node emission
     *       by exact name, and suppression removes emission rather than readability.
     *   <li>the sink's consumer group, so {@code kafka} is handed a node it cannot attribute.
     * </ol>
     *
     * <p>A <b>source</b> connector gets no group, and that is not an omission: a source produces,
     * commits no offsets, and has no consumer group to name.
     */
    private static List<Backing> backings(ConnectorInfo connector, ConnectConfig config) {
        SequencedSet<Backing> backings = new LinkedHashSet<>();
        backings.add(new Backing(PLUGIN_ID, CONNECTOR_KIND, connector.name()));
        if (config.workload() != null) {
            backings.add(new Backing(
                    config.workload().plugin(), config.workload().kind(), config.workload().reference()));
        }
        if (connector.isSink()) {
            String group = connector.config(GROUP_OVERRIDE);
            backings.add(new Backing(
                    CONSUMER_GROUP_DOMAIN,
                    CONSUMER_GROUP_KIND,
                    group == null ? "connect-" + connector.name() : group));
        }
        return List.copyOf(backings);
    }

    /**
     * ADR-0041, and ADR-0002: the relation is {@code SOURCES_FROM}, whose orientation is REVERSED,
     * so the stored edge runs <b>topic → connector</b> — the direction the data flows, whatever the
     * verb reads like aloud. Downstream traversal is then "follow outgoing" with no orientation
     * lookup anywhere.
     *
     * <p>The topic key is the topic's name, which under ADR-0020 is exactly the key {@code kafka}
     * mints for the same topic. Neither plugin coordinates with the other and they land on one row.
     */
    private static List<DiscoveredEdge> sourcesFrom(ConnectorInfo connector) {
        String topics = connector.config(TOPICS);
        if (topics == null) {
            if (connector.config(TOPICS_REGEX) != null) {
                // The accepted cost stated out loud once per poll rather than left to be discovered
                // from an absent edge. A regex names a pattern, not a topic, and resolving it needs
                // the cluster's topic list — which is another plugin's scope (ADR-0012).
                log.info(
                        "connector {} routes by `topics.regex`; ADR-0041 parses the `topics` key alone, "
                                + "so it contributes no edge",
                        connector.name());
            }
            return List.of();
        }
        List<DiscoveredEdge> edges = new ArrayList<>();
        for (String topic : topics.split(",")) {
            String trimmed = topic.trim();
            if (!trimmed.isEmpty()) {
                edges.add(new DiscoveredEdge(trimmed, connector.name().trim(), "SOURCES_FROM"));
            }
        }
        return edges;
    }

    private static void put(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value.trim());
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
