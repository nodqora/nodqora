package io.nodqora.core.fold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

import io.nodqora.core.graph.FoldedEdge;
import io.nodqora.core.graph.FoldedGraph;
import io.nodqora.core.graph.FoldedNode;
import io.nodqora.core.registry.PluginOrder;
import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.DiscoveredEdge;
import io.nodqora.plugin.api.DiscoveredNode;
import io.nodqora.plugin.api.DiscoveredOwner;
import io.nodqora.plugin.api.Link;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The fold is the slice's one hard idea (ADR-0043). It is a pure recomputation of an environment
 * from the stored snapshots, and it is order-independent: the same stored snapshots produce the
 * same graph whatever order they arrived in.
 *
 * <p>These tests name plugins the way the fixture does. The core's <em>source</em> may not
 * (ADR-0015, checked by {@code CoreKnowsNoPluginTest}); its tests exercise the real orders because
 * the orders are the thing under test.
 */
class GraphFoldTest {

    private static final PluginOrder ORDER = new PluginOrder(
            List.of("yaml", "kubernetes", "kafka", "connect"),
            List.of("yaml", "connect", "kubernetes", "kafka"));

    private final GraphFold fold = new GraphFold(ORDER);

    private static SnapshotView snapshot(String pluginId, List<DiscoveredNode> nodes, List<DiscoveredEdge> edges) {
        return new SnapshotView(pluginId, nodes, edges, List.of());
    }

    private static FoldedNode nodeKeyed(FoldedGraph graph, String key) {
        return graph.nodes().stream()
                .filter(node -> node.key().equalsIgnoreCase(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node keyed " + key + " in " + graph.nodes()));
    }

    @Nested
    class ScalarsSettleByOneGlobalPrecedence {

        @Test
        void first_non_null_in_precedence_order_wins() {
            FoldedGraph graph = fold.fold(List.of(
                    snapshot("kubernetes", List.of(new DiscoveredNode(
                            "payments-enricher", "service", null, null, "payments-platform",
                            List.of(), List.of(), Map.of())), List.of()),
                    snapshot("yaml", List.of(new DiscoveredNode(
                            "payments-enricher", null, "Payments Enricher", null, null,
                            List.of(), List.of(), Map.of())), List.of())));

            FoldedNode node = nodeKeyed(graph, "payments-enricher");
            assertThat(node.displayName()).isEqualTo("Payments Enricher");
            // `yaml` outranks `kubernetes` but said nothing about type or owner, and null is
            // no opinion — so the lower-precedence value stands rather than blanking it.
            assertThat(node.type()).isEqualTo("service");
            assertThat(node.ownerKey()).isEqualTo("payments-platform");
        }

        @Test
        void the_fold_is_order_independent() {
            List<SnapshotView> snapshots = List.of(
                    snapshot("kubernetes", List.of(new DiscoveredNode(
                            "payments-enricher", "service", null, null, null,
                            List.of(), List.of(), Map.of())), List.of()),
                    snapshot("yaml", List.of(new DiscoveredNode(
                            "payments-enricher", "worker", null, null, null,
                            List.of(), List.of(), Map.of())), List.of()));

            assertThat(fold.fold(snapshots)).isEqualTo(fold.fold(snapshots.reversed()));
            assertThat(nodeKeyed(fold.fold(snapshots.reversed()), "payments-enricher").type())
                    .isEqualTo("worker");
        }

        @Test
        void two_plugins_minting_one_key_in_different_case_land_on_one_node() {
            FoldedGraph graph = fold.fold(List.of(
                    snapshot("yaml", List.of(DiscoveredNode.ofKey("Payments-Enricher")), List.of()),
                    snapshot("kubernetes", List.of(DiscoveredNode.ofKey("payments-enricher")), List.of())));

            // ADR-0020: the case-folded index is what keeps hand-authoring forgiving without lying
            // about the name a source system uses. The verbatim spelling comes from precedence.
            assertThat(graph.nodes()).hasSize(1);
            assertThat(graph.nodes().getFirst().key()).isEqualTo("Payments-Enricher");
            assertThat(graph.nodes().getFirst().sources()).containsExactly("yaml", "kubernetes");
        }
    }

    @Nested
    class CollectionsAreAdditiveWithElementIdentity {

        @Test
        void links_deduplicate_on_rel_and_normalized_url_blind_to_who_said_it() {
            FoldedGraph graph = fold.fold(List.of(
                    snapshot("kubernetes", List.of(new DiscoveredNode(
                            "payments-enricher", null, null, null, null,
                            List.of(new Link("repository", "Repository", "https://github.com/acme/payments-enricher")),
                            List.of(), Map.of())), List.of()),
                    snapshot("yaml", List.of(new DiscoveredNode(
                            "payments-enricher", null, null, null, null,
                            List.of(new Link("repository", "Repository", "github.com/acme/payments-enricher")),
                            List.of(), Map.of())), List.of())));

            // ADR-0044: keying by plugin renders two identical Repository buttons on the fixture's
            // most-linked node; deduplicating the raw string does the same whenever one writer
            // supplied a scheme and the other did not.
            assertThat(nodeKeyed(graph, "payments-enricher").links()).hasSize(1);
        }

        @Test
        void two_writers_with_one_rel_and_different_urls_produce_two_visible_links() {
            FoldedGraph graph = fold.fold(List.of(
                    snapshot("yaml", List.of(new DiscoveredNode(
                            "n", null, null, null, null,
                            List.of(new Link("runbook", "Runbook", "https://wiki/a")),
                            List.of(), Map.of())), List.of()),
                    snapshot("kubernetes", List.of(new DiscoveredNode(
                            "n", null, null, null, null,
                            List.of(new Link("runbook", "Runbook", "https://wiki/b")),
                            List.of(), Map.of())), List.of())));

            // ADR-0031's failure direction: a visibly-wrong extra beats a silent drop, and it lets
            // a human see that two sources disagree.
            assertThat(nodeKeyed(graph, "n").links())
                    .extracting(Link::url)
                    .containsExactly("https://wiki/a", "https://wiki/b");
        }

        @Test
        void backings_union_and_order_by_plugin_kind_reference() {
            FoldedGraph graph = fold.fold(List.of(
                    snapshot("kubernetes", List.of(new DiscoveredNode(
                            "payments-enricher", null, null, null, null, List.of(),
                            List.of(new Backing("kubernetes", "Deployment", "enricher-v2")), Map.of())), List.of()),
                    snapshot("yaml", List.of(new DiscoveredNode(
                            "payments-enricher", null, null, null, null, List.of(),
                            List.of(new Backing("kafka", "consumer-group", "enrich-consumer-prod")),
                            Map.of())), List.of())));

            assertThat(nodeKeyed(graph, "payments-enricher").backings())
                    .containsExactly(
                            new Backing("kafka", "consumer-group", "enrich-consumer-prod"),
                            new Backing("kubernetes", "Deployment", "enricher-v2"));
        }

        @Test
        void metadata_is_filed_under_the_plugin_that_emitted_it() {
            FoldedGraph graph = fold.fold(List.of(snapshot("kafka", List.of(new DiscoveredNode(
                    "payments.events.raw.v1", null, null, null, null, List.of(), List.of(),
                    Map.of("partitions", 12, "retentionMs", 604800000L))), List.of())));

            assertThat(nodeKeyed(graph, "payments.events.raw.v1").metadata())
                    .containsExactly(entry("kafka", Map.of("partitions", 12, "retentionMs", 604800000L)));
        }
    }

    @Nested
    class Sources {

        @Test
        void sources_are_the_union_of_node_entries_and_edge_endpoints_in_registry_order() {
            FoldedGraph graph = fold.fold(List.of(
                    snapshot("kafka", List.of(DiscoveredNode.ofKey("payments.events.enriched.v1")), List.of()),
                    snapshot("yaml", List.of(DiscoveredNode.ofKey("payments.events.enriched.v1")), List.of()),
                    snapshot("connect", List.of(), List.of(new DiscoveredEdge(
                            "payments.events.enriched.v1", "payments-es-sink", "SOURCES_FROM")))));

            // ADR-0079, the ticket's closest call: `connect` did assert that topic exists, by
            // naming it. Provenance inflates, and the reading is defensible rather than false.
            assertThat(nodeKeyed(graph, "payments.events.enriched.v1").sources())
                    .containsExactly("yaml", "kafka", "connect");
        }
    }

    @Nested
    class StubNodes {

        @Test
        void an_edge_endpoint_no_snapshot_carries_materializes_a_bare_node() {
            FoldedGraph graph = fold.fold(List.of(snapshot("yaml",
                    List.of(DiscoveredNode.ofKey("payments-es-sink")),
                    List.of(new DiscoveredEdge("payments-es-sink", "payments-events-v1", "WRITES_TO")))));

            // ADR-0048: a canvas edge needs two nodes, and a visibly-wrong extra node beats a
            // silently-missing relationship. There is no stub flag — this is the empty case.
            FoldedNode stub = nodeKeyed(graph, "payments-events-v1");
            assertThat(stub.type()).isNull();
            assertThat(stub.displayName()).isNull();
            assertThat(stub.ownerKey()).isNull();
            assertThat(stub.backings()).isEmpty();
            assertThat(stub.links()).isEmpty();
            assertThat(stub.sources()).containsExactly("yaml");
        }

        @Test
        void an_endpoint_differing_only_in_case_reaches_the_real_node() {
            FoldedGraph graph = fold.fold(List.of(snapshot("yaml",
                    List.of(DiscoveredNode.ofKey("payments-api")),
                    List.of(new DiscoveredEdge("Payments-API", "payments.events.raw.v1", "PRODUCES_TO")))));

            // ADR-0045: or ADR-0048 materializes a second stub differing only in case, which
            // ADR-0020's folded uniqueness exists to prevent.
            assertThat(graph.nodes()).extracting(FoldedNode::key)
                    .containsExactly("payments-api", "payments.events.raw.v1");
            assertThat(graph.edges()).extracting(FoldedEdge::fromKey).containsExactly("payments-api");
        }
    }

    @Nested
    class Edges {

        @Test
        void the_same_tuple_from_two_plugins_is_one_edge() {
            DiscoveredEdge edge = new DiscoveredEdge(
                    "payments.events.enriched.v1", "payments-es-sink", "SOURCES_FROM");
            FoldedGraph graph = fold.fold(List.of(
                    snapshot("yaml", List.of(), List.of(edge)),
                    snapshot("connect", List.of(), List.of(edge))));

            assertThat(graph.edges()).singleElement()
                    .satisfies(folded -> assertThat(folded.sources()).containsExactly("yaml", "connect"));
        }

        @Test
        void two_writers_disagreeing_about_the_relation_render_two_parallel_edges() {
            FoldedGraph graph = fold.fold(List.of(
                    snapshot("yaml", List.of(), List.of(new DiscoveredEdge("a", "b", "CALLS"))),
                    snapshot("connect", List.of(), List.of(new DiscoveredEdge("a", "b", "WRITES_TO")))));

            // ADR-0045: taken deliberately. The alternative silently discards a plugin's assertion
            // in exactly the situation where a human most needs to see the disagreement.
            assertThat(graph.edges()).extracting(FoldedEdge::relation)
                    .containsExactlyInAnyOrder("CALLS", "WRITES_TO");
        }

        @Test
        void a_relation_is_matched_exactly_and_never_case_folded() {
            FoldedGraph graph = fold.fold(List.of(
                    snapshot("yaml", List.of(), List.of(new DiscoveredEdge("a", "b", "CALLS"))),
                    snapshot("connect", List.of(), List.of(new DiscoveredEdge("a", "b", "calls")))));

            // ADR-0071: a relation is a vocabulary id from a registry of built-ins, not discovered
            // data. Folding it would merge a typo into the vocabulary.
            assertThat(graph.edges()).hasSize(2);
        }
    }

    @Nested
    class Owners {

        @Test
        void owners_fold_by_the_same_precedence_as_nodes() {
            FoldedGraph graph = fold.fold(List.of(
                    new SnapshotView("kubernetes", List.of(), List.of(),
                            List.of(new DiscoveredOwner("payments-platform", "PP", null, null))),
                    new SnapshotView("yaml", List.of(), List.of(),
                            List.of(new DiscoveredOwner("Payments-Platform", "Payments Platform",
                                    "#payments-oncall", "Payments SRE")))));

            assertThat(graph.owners()).singleElement().satisfies(owner -> {
                assertThat(owner.key()).isEqualTo("Payments-Platform");
                assertThat(owner.displayName()).isEqualTo("Payments Platform");
                assertThat(owner.channel()).isEqualTo("#payments-oncall");
            });
        }

        @Test
        void an_owner_key_resolving_to_no_owner_is_ordinary() {
            FoldedGraph graph = fold.fold(List.of(snapshot("kubernetes", List.of(new DiscoveredNode(
                    "payments-api", null, null, null, "payments-platform",
                    List.of(), List.of(), Map.of())), List.of())));

            // ADR-0048: the expected steady state for a node whose manifests are annotated before
            // anyone has written a YAML owner block. No PARTIAL, no warning, no materialized Owner.
            assertThat(nodeKeyed(graph, "payments-api").ownerKey()).isEqualTo("payments-platform");
            assertThat(graph.owners()).isEmpty();
        }
    }

    @Nested
    class CanonicalOrdering {

        @Test
        void output_collections_are_canonically_ordered_whatever_the_input_order() {
            FoldedGraph graph = fold.fold(List.of(snapshot("yaml",
                    List.of(DiscoveredNode.ofKey("zebra"), DiscoveredNode.ofKey("alpha")),
                    List.of(
                            new DiscoveredEdge("zebra", "alpha", "CALLS"),
                            new DiscoveredEdge("alpha", "zebra", "CALLS")))));

            // ADR-0050: comparing collections as built makes every poll a spurious diff, and
            // `updatedAt` degenerates into a poll clock — silently, in the direction where nothing
            // looks broken.
            assertThat(graph.nodes()).extracting(FoldedNode::key).containsExactly("alpha", "zebra");
            assertThat(graph.edges()).extracting("fromKey", "toKey")
                    .containsExactly(tuple("alpha", "zebra"), tuple("zebra", "alpha"));
        }
    }
}
