package io.nodqora.core.api;

import io.nodqora.core.api.ApiDocuments.EdgeDocument;
import io.nodqora.core.api.ApiDocuments.EnvironmentRef;
import io.nodqora.core.api.ApiDocuments.GraphDocument;
import io.nodqora.core.api.ApiDocuments.MetaDocument;
import io.nodqora.core.api.ApiDocuments.NodeDocument;
import io.nodqora.core.api.ApiDocuments.NodeStateDocument;
import io.nodqora.core.api.ApiDocuments.OwnerDocument;
import io.nodqora.core.api.ApiDocuments.PluginOutcome;
import io.nodqora.core.api.ApiDocuments.PluginRef;
import io.nodqora.core.api.ApiDocuments.Refresh;
import io.nodqora.core.api.ApiDocuments.Source;
import io.nodqora.core.api.ApiDocuments.StateDocument;
import io.nodqora.core.config.BoundConfiguration;
import io.nodqora.core.graph.GraphRecords.GraphRecord;
import io.nodqora.core.graph.Keys;
import io.nodqora.core.registry.RelationDescriptors;
import io.nodqora.core.store.GraphStore;
import io.nodqora.core.store.NodeStateStore;
import io.nodqora.core.store.SnapshotHeader;
import io.nodqora.core.store.SnapshotStore;
import io.nodqora.plugin.api.DiscoveryCapability;
import io.nodqora.plugin.api.HealthCapability;
import io.nodqora.plugin.api.Plugin;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Assembles the three documents. Read-only end to end: the MVP has no write of any kind. */
@Service
public class GraphDocuments {

    /**
     * ADR-0010: exactly two capabilities, either or both. Each names where its roster comes from and
     * where its last poll was recorded, so the read path picks a capability rather than branching on
     * a string in two places that could disagree.
     */
    private enum Capability {
        DISCOVERY,
        HEALTH;

        List<BoundConfiguration.ConfiguredCapability> configured(
                BoundConfiguration configuration, String environmentKey) {
            return this == DISCOVERY
                    ? configuration.discoveryPairs(environmentKey)
                    : configuration.healthPairs(environmentKey);
        }

        Map<String, SnapshotHeader> reported(SnapshotStore snapshots, String environmentKey) {
            // The health store arrives with the health loop in slice 3; until then nothing has
            // reported, which under ADR-0085 is a null outcome rather than a missing entry.
            return this == DISCOVERY
                    ? snapshots.headers(environmentKey).stream()
                            .collect(Collectors.toMap(SnapshotHeader::pluginId, header -> header))
                    : Map.of();
        }
    }

    private final BoundConfiguration configuration;
    private final GraphStore graph;
    private final NodeStateStore states;
    private final SnapshotStore snapshots;

    public GraphDocuments(
            BoundConfiguration configuration, GraphStore graph, NodeStateStore states, SnapshotStore snapshots) {
        this.configuration = configuration;
        this.graph = graph;
        this.states = states;
        this.snapshots = snapshots;
    }

    public MetaDocument meta() {
        return new MetaDocument(
                configuration.environments().stream()
                        .map(environment -> new EnvironmentRef(environment.key(), environment.displayName()))
                        .toList(),
                configuration.registeredPlugins().stream()
                        .map(plugin -> new PluginRef(plugin.id(), plugin.displayLabel(), capabilities(plugin)))
                        .toList(),
                new Refresh(
                        configuration.refresh().discovery().toSeconds(),
                        configuration.refresh().health().toSeconds()));
    }

    /** ADR-0010: a capability is present iff the bean implements the interface. */
    private static List<String> capabilities(Plugin<?> plugin) {
        List<String> capabilities = new ArrayList<>();
        if (plugin instanceof DiscoveryCapability<?>) {
            capabilities.add(Capability.DISCOVERY.name());
        }
        if (plugin instanceof HealthCapability<?>) {
            capabilities.add(Capability.HEALTH.name());
        }
        return capabilities;
    }

    @Transactional(readOnly = true)
    public GraphDocument graph(String environmentKey) {
        requireKnown(environmentKey);
        GraphRecord rows = graph.read(environmentKey);
        Map<String, Map<String, Instant>> confirmedAt = snapshots.confirmedAt(environmentKey);

        return new GraphDocument(
                environmentRef(environmentKey),
                rows.nodes().stream()
                        .map(node -> new NodeDocument(
                                node.key(),
                                node.type(),
                                node.displayName(),
                                node.description(),
                                node.ownerKey(),
                                node.links(),
                                node.backings(),
                                node.metadata(),
                                sources(node.sources(), confirmedAt.get(Keys.folded(node.key()))),
                                node.discoveredAt(),
                                node.updatedAt()))
                        .toList(),
                rows.edges().stream()
                        .map(edge -> new EdgeDocument(
                                edge.fromKey(),
                                edge.toKey(),
                                edge.relation(),
                                edge.metadata(),
                                edge.sources(),
                                edge.discoveredAt(),
                                edge.updatedAt()))
                        .toList(),
                rows.owners().stream()
                        .map(owner -> new OwnerDocument(
                                owner.key(), owner.displayName(), owner.channel(), owner.onCall()))
                        .toList(),
                // ADR-0077: the global set, projected over every environment's descriptor entries.
                // A superset of what this environment uses — harmless, and stated so nobody later
                // mistakes it for environment-scoped descriptors.
                snapshots.typeDescriptors(configuration.byPluginPrecedence(), configuration.byEnvironmentOrder()),
                RelationDescriptors.builtIns(),
                outcomes(environmentKey, Capability.DISCOVERY));
    }

    private List<Source> sources(List<String> plugins, Map<String, Instant> confirmedAt) {
        Map<String, Instant> confirmations = confirmedAt == null ? Map.of() : confirmedAt;
        return plugins.stream()
                .map(plugin -> new Source(plugin, confirmations.get(plugin)))
                .toList();
    }

    @Transactional(readOnly = true)
    public StateDocument state(String environmentKey) {
        requireKnown(environmentKey);
        List<NodeStateStore.NodeStateRow> rows = states.read(environmentKey);

        return new StateDocument(
                environmentKey,
                // As stale as the stalest observation, so the number can never overstate (ADR-0072).
                rows.stream()
                        .map(NodeStateStore.NodeStateRow::observedAt)
                        .filter(java.util.Objects::nonNull)
                        .min(Comparator.naturalOrder())
                        .orElse(null),
                rows.stream()
                        .map(row -> new NodeStateDocument(
                                row.nodeKey(),
                                row.health().name(),
                                row.rawSignal(),
                                row.metrics(),
                                row.observedAt()))
                        .toList(),
                outcomes(environmentKey, Capability.HEALTH));
    }

    /**
     * ADR-0085: every configured {@code (plugin, capability)} pair for this environment, left-joined
     * with the store. The denominator is a config count, so it never shrinks because nothing has
     * reported — which is what makes a cold environment distinguishable from an empty one.
     */
    private List<PluginOutcome> outcomes(String environmentKey, Capability capability) {
        Map<String, SnapshotHeader> reported = capability.reported(snapshots, environmentKey);

        return capability.configured(configuration, environmentKey).stream()
                .map(pair -> {
                    SnapshotHeader header = reported.get(pair.plugin().id());
                    return new PluginOutcome(
                            pair.plugin().id(),
                            capability.name(),
                            header == null ? null : header.outcome().name(),
                            header == null ? List.of() : header.reasons(),
                            header == null ? null : header.recordedAt());
                })
                .toList();
    }

    private EnvironmentRef environmentRef(String environmentKey) {
        return configuration.environments().stream()
                .filter(environment -> environment.key().equals(environmentKey))
                .map(environment -> new EnvironmentRef(environment.key(), environment.displayName()))
                .findFirst()
                .orElseThrow(() -> new UnknownEnvironmentException(environmentKey));
    }

    /**
     * ADR-0052: the environment key is matched <em>exactly</em>, unlike a node key. It is
     * operator-authored config, not a key four plugins mint independently and must collide on purpose.
     */
    private void requireKnown(String environmentKey) {
        if (!configuration.knows(environmentKey)) {
            throw new UnknownEnvironmentException(environmentKey);
        }
    }
}
