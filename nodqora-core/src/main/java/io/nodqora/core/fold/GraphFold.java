package io.nodqora.core.fold;

import io.nodqora.core.graph.FoldedEdge;
import io.nodqora.core.graph.FoldedGraph;
import io.nodqora.core.graph.FoldedNode;
import io.nodqora.core.graph.FoldedOwner;
import io.nodqora.core.graph.Keys;
import io.nodqora.core.registry.PluginOrder;
import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.DiscoveredEdge;
import io.nodqora.plugin.api.DiscoveredNode;
import io.nodqora.plugin.api.DiscoveredOwner;
import io.nodqora.plugin.api.Link;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The pure recomputation of an environment's Node, Edge and Owner rows from the snapshot store
 * (ADR-0043). This is the slice's one hard idea.
 *
 * <p>Three properties hold and are load-bearing:
 *
 * <ul>
 *   <li><b>It is a fold, not a merge.</b> There is no "existing populated value" to guard, only the
 *       snapshots, which is why deletion, {@code null}, stale composed links and value explanation
 *       stop being designed and start being arithmetic.
 *   <li><b>It is order-independent.</b> The same stored snapshots produce the same graph whatever
 *       order they arrived in, because the fold sorts by precedence itself rather than trusting
 *       arrival order. Nothing may express a <em>negative</em> assertion: every snapshot says only
 *       "here is what I found" (ADR-0051).
 *   <li><b>Its output is canonically ordered.</b> Every collection is ordered by a rule, because
 *       ADR-0050 makes ordering a correctness obligation rather than a tidiness one.
 * </ul>
 *
 * <p>Say the fold <em>recomputes</em> a node. Do not say the merge <em>updates</em> one.
 */
public class GraphFold {

    private final PluginOrder order;

    public GraphFold(PluginOrder order) {
        this.order = order;
    }

    public FoldedGraph fold(List<SnapshotView> snapshots) {
        List<SnapshotView> byPrecedence = snapshots.stream()
                .sorted(Comparator.comparingInt(snapshot -> order.precedenceRank(snapshot.pluginId())))
                .toList();

        EndpointIndex endpoints = EndpointIndex.of(byPrecedence);
        Map<String, List<Claim<DiscoveredNode>>> nodeClaims =
                claims(byPrecedence, SnapshotView::nodes, node -> Keys.folded(node.key()));

        List<FoldedNode> nodes = foldNodes(nodeClaims, endpoints);
        Map<String, String> canonicalKeys = new LinkedHashMap<>();
        nodes.forEach(node -> canonicalKeys.put(Keys.folded(node.key()), node.key()));

        return new FoldedGraph(
                nodes.stream().sorted(Comparator.comparing(node -> Keys.folded(node.key()))).toList(),
                foldEdges(byPrecedence, canonicalKeys),
                foldOwners(claims(byPrecedence, SnapshotView::owners, owner -> Keys.folded(owner.key()))));
    }

    // ---------------------------------------------------------------- nodes

    private List<FoldedNode> foldNodes(Map<String, List<Claim<DiscoveredNode>>> claims, EndpointIndex endpoints) {
        SequencedSet<String> keys = new LinkedHashSet<>(claims.keySet());
        keys.addAll(endpoints.keys());

        List<FoldedNode> nodes = new ArrayList<>(keys.size());
        for (String folded : keys) {
            List<Claim<DiscoveredNode>> carried = claims.getOrDefault(folded, List.of());
            nodes.add(new FoldedNode(
                    // A stub has no node entry to take a spelling from, so the edge that named it
                    // supplies one (ADR-0048).
                    carried.isEmpty()
                            ? endpoints.spelling(folded)
                            : Keys.verbatim(carried.getFirst().value().key()),
                    first(carried, DiscoveredNode::type),
                    first(carried, DiscoveredNode::displayName),
                    first(carried, DiscoveredNode::description),
                    first(carried, DiscoveredNode::ownerKey),
                    links(carried),
                    backings(carried),
                    metadata(carried, DiscoveredNode::metadata),
                    sources(union(plugins(carried), endpoints.plugins(folded)))));
        }
        return nodes;
    }

    /**
     * ADR-0044: one global precedence order, first non-{@code null} wins, applied identically to
     * {@code type}, {@code displayName}, {@code description} and {@code ownerKey}. Nothing else on a
     * node is contestable.
     */
    private static String first(List<Claim<DiscoveredNode>> claims, Function<DiscoveredNode, String> field) {
        return claims.stream()
                .map(claim -> field.apply(claim.value()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * ADR-0044: additive, deduplicated on {@code (rel, normalized url)} after scheme-prepending and
     * <em>blind to which plugin said it</em>. Keying by {@code rel} alone would silently drop a real
     * second workload link; keying by plugin renders two identical Repository buttons on the
     * fixture's most-linked node.
     */
    private static List<Link> links(List<Claim<DiscoveredNode>> claims) {
        Map<String, Link> byIdentity = new LinkedHashMap<>();
        for (Claim<DiscoveredNode> claim : claims) {
            for (Link link : claim.value().links()) {
                byIdentity.putIfAbsent(link.rel() + " " + normalizeUrl(link.url()), link);
            }
        }
        return List.copyOf(byIdentity.values());
    }

    private static String normalizeUrl(String url) {
        String trimmed = url.trim();
        return trimmed.contains("://") ? trimmed : "https://" + trimmed;
    }

    /** ADR-0022, ADR-0050: union, ordered by the element identity, which is already a total order. */
    private static List<Backing> backings(List<Claim<DiscoveredNode>> claims) {
        return claims.stream()
                .flatMap(claim -> claim.value().backings().stream())
                .distinct()
                .sorted(Comparator.comparing(Backing::plugin)
                        .thenComparing(Backing::kind)
                        .thenComparing(Backing::reference))
                .toList();
    }

    /**
     * ADR-0006: a map keyed by plugin id. A plugin emits only its own namespace, so this is where it
     * is filed, and there is no way for one plugin to write into another's.
     */
    private static <T> Map<String, Object> metadata(List<Claim<T>> claims, Function<T, Map<String, Object>> field) {
        Map<String, Object> namespaced = new TreeMap<>();
        for (Claim<T> claim : claims) {
            Map<String, Object> own = field.apply(claim.value());
            if (!own.isEmpty()) {
                namespaced.put(claim.pluginId(), new TreeMap<>(own));
            }
        }
        return Map.copyOf(namespaced);
    }

    // ---------------------------------------------------------------- edges

    private List<FoldedEdge> foldEdges(List<SnapshotView> snapshots, Map<String, String> canonicalKeys) {
        Map<String, EdgeGroup> groups = new LinkedHashMap<>();
        for (SnapshotView snapshot : snapshots) {
            for (DiscoveredEdge edge : snapshot.edges()) {
                // ADR-0045: identity is the full tuple, endpoints case-folded and the relation
                // matched exactly. Nothing on an edge is contestable, so this is set union.
                String identity =
                        Keys.folded(edge.fromKey()) + " " + Keys.folded(edge.toKey()) + " " + edge.relation();
                groups.computeIfAbsent(identity, ignored -> new EdgeGroup(edge)).add(snapshot.pluginId(), edge);
            }
        }
        return groups.values().stream()
                .map(group -> group.toFolded(canonicalKeys, this::sources))
                .sorted(Comparator.comparing((FoldedEdge edge) -> Keys.folded(edge.fromKey()))
                        .thenComparing(edge -> Keys.folded(edge.toKey()))
                        .thenComparing(FoldedEdge::relation))
                .toList();
    }

    private static final class EdgeGroup {

        private final DiscoveredEdge first;
        private final List<Claim<DiscoveredEdge>> claims = new ArrayList<>();
        private final SequencedSet<String> plugins = new LinkedHashSet<>();

        EdgeGroup(DiscoveredEdge first) {
            this.first = first;
        }

        void add(String pluginId, DiscoveredEdge edge) {
            claims.add(new Claim<>(pluginId, edge));
            plugins.add(pluginId);
        }

        FoldedEdge toFolded(
                Map<String, String> canonicalKeys, Function<SequencedSet<String>, List<String>> sources) {
            return new FoldedEdge(
                    // An endpoint is spelled the way its node is spelled, so a client can match
                    // edges to nodes without folding (ADR-0045).
                    canonicalKeys.getOrDefault(Keys.folded(first.fromKey()), Keys.verbatim(first.fromKey())),
                    canonicalKeys.getOrDefault(Keys.folded(first.toKey()), Keys.verbatim(first.toKey())),
                    first.relation(),
                    metadata(claims, DiscoveredEdge::metadata),
                    sources.apply(plugins));
        }
    }

    // ---------------------------------------------------------------- owners

    private List<FoldedOwner> foldOwners(Map<String, List<Claim<DiscoveredOwner>>> claims) {
        return claims.values().stream()
                .map(carried -> new FoldedOwner(
                        Keys.verbatim(carried.getFirst().value().key()),
                        firstOwner(carried, DiscoveredOwner::displayName),
                        firstOwner(carried, DiscoveredOwner::channel),
                        firstOwner(carried, DiscoveredOwner::onCall)))
                .sorted(Comparator.comparing(owner -> Keys.folded(owner.key())))
                .toList();
    }

    private static String firstOwner(List<Claim<DiscoveredOwner>> claims, Function<DiscoveredOwner, String> field) {
        return claims.stream()
                .map(claim -> field.apply(claim.value()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    // ---------------------------------------------------------------- shared

    /**
     * ADR-0079: the union of plugins carrying the key as a node entry and plugins naming it as an
     * edge endpoint, in registry order (ADR-0050). The rule is uniform, so a stub node is literally
     * the empty case rather than a special rule, at the cost of a plugin appearing in the
     * {@code sources[]} of a node it only named.
     */
    private List<String> sources(SequencedSet<String> plugins) {
        return plugins.stream()
                .sorted(Comparator.comparingInt(order::registryRank).thenComparing(Function.identity()))
                .toList();
    }

    private static SequencedSet<String> union(SequencedSet<String> a, SequencedSet<String> b) {
        SequencedSet<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return union;
    }

    private static <T> SequencedSet<String> plugins(List<Claim<T>> claims) {
        SequencedSet<String> plugins = new LinkedHashSet<>();
        claims.forEach(claim -> plugins.add(claim.pluginId()));
        return plugins;
    }

    private static <T> Map<String, List<Claim<T>>> claims(
            List<SnapshotView> byPrecedence, Function<SnapshotView, List<T>> entries, Function<T, String> identity) {
        Map<String, List<Claim<T>>> claims = new LinkedHashMap<>();
        for (SnapshotView snapshot : byPrecedence) {
            for (T entry : entries.apply(snapshot)) {
                claims.computeIfAbsent(identity.apply(entry), ignored -> new ArrayList<>())
                        .add(new Claim<>(snapshot.pluginId(), entry));
            }
        }
        return claims;
    }

    /** One plugin's opinion about one thing, already in precedence order by construction. */
    private record Claim<T>(String pluginId, T value) {}

    /** Which plugins named a key as an edge endpoint, and how they spelled it. */
    private record EndpointIndex(Map<String, SequencedSet<String>> plugins, Map<String, String> spellings) {

        static EndpointIndex of(List<SnapshotView> byPrecedence) {
            Map<String, SequencedSet<String>> plugins = new LinkedHashMap<>();
            Map<String, String> spellings = new LinkedHashMap<>();
            for (SnapshotView snapshot : byPrecedence) {
                for (DiscoveredEdge edge : snapshot.edges()) {
                    for (String endpoint : List.of(edge.fromKey(), edge.toKey())) {
                        String folded = Keys.folded(endpoint);
                        plugins.computeIfAbsent(folded, ignored -> new LinkedHashSet<>()).add(snapshot.pluginId());
                        spellings.putIfAbsent(folded, Keys.verbatim(endpoint));
                    }
                }
            }
            return new EndpointIndex(plugins, spellings);
        }

        SequencedSet<String> keys() {
            return new LinkedHashSet<>(plugins.keySet());
        }

        SequencedSet<String> plugins(String foldedKey) {
            return Optional.ofNullable(plugins.get(foldedKey)).orElseGet(LinkedHashSet::new);
        }

        String spelling(String foldedKey) {
            return spellings.get(foldedKey);
        }
    }
}
