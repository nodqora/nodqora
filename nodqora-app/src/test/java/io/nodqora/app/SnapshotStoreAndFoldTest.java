// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.nodqora.core.fold.DiscoveryFoldRunner;
import io.nodqora.core.graph.GraphRecords.NodeRecord;
import io.nodqora.core.store.GraphStore;
import io.nodqora.core.store.SnapshotStore;
import io.nodqora.plugin.api.DiscoveredEdge;
import io.nodqora.plugin.api.DiscoveredNode;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.Outcome;
import io.nodqora.plugin.api.OutcomeStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The snapshot store's transition table and the fold's timestamp rule, against a real database.
 *
 * <p>These use a fictional second plugin id, because what is under test is the engine's arithmetic
 * over any plugin's snapshot rather than anything {@code yaml} does.
 */
class SnapshotStoreAndFoldTest extends NodqoraIntegrationTest {

    private static final String ENVIRONMENT = "production";

    @Autowired
    SnapshotStore snapshots;

    @Autowired
    GraphStore graph;

    @Autowired
    DiscoveryFoldRunner folds;

    private void land(DiscoveryResult result) {
        // A second plugin id needs a `plugin` mirror row for ADR-0074's cascade to hang off.
        jdbc.update("insert into plugin (id) values ('probe') on conflict do nothing");
        snapshots.record(ENVIRONMENT, "probe", result, Instant.now());
        folds.run(ENVIRONMENT);
    }

    private static DiscoveryResult snapshot(Outcome outcome, String... keys) {
        return new DiscoveryResult(
                List.of(keys).stream().map(DiscoveredNode::ofKey).toList(),
                List.of(),
                List.of(),
                List.of(),
                outcome);
    }

    private NodeRecord node(String key) {
        return graph.read(ENVIRONMENT).nodes().stream()
                .filter(candidate -> candidate.key().equalsIgnoreCase(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node keyed " + key));
    }

    private boolean has(String key) {
        return graph.read(ENVIRONMENT).nodes().stream().anyMatch(node -> node.key().equalsIgnoreCase(key));
    }

    @Nested
    class OutcomeDrivesTheStore {

        @Test
        void complete_replaces_wholesale_so_an_absent_key_is_deleted_immediately() {
            land(snapshot(Outcome.complete(), "probe-a", "probe-b"));
            assertThat(has("probe-b")).isTrue();

            land(snapshot(Outcome.complete(), "probe-a"));

            // ADR-0047: no N-consecutive rule and no delta threshold. Deletion here is
            // non-destructive and self-healing; over-retention is permanent and corrupts
            // drift-is-absence, which is the mechanism the environment switcher rests on.
            assertThat(has("probe-b")).isFalse();
            assertThat(has("probe-a")).isTrue();
        }

        @Test
        void partial_upserts_the_keys_present_and_retains_the_keys_absent() {
            land(snapshot(Outcome.complete(), "probe-a", "probe-b"));

            land(snapshot(Outcome.partial("a scope unit yielded nothing"), "probe-a"));

            // ADR-0046: the plugin has said it was blind somewhere, so its absences carry no
            // information.
            assertThat(has("probe-b")).isTrue();
        }

        @Test
        void failed_changes_nothing_at_all() {
            land(snapshot(Outcome.complete(), "probe-a", "probe-b"));

            land(DiscoveryResult.failed("the cluster did not answer"));

            // ADR-0012 made the value explicit so a FAILED snapshot is never mistaken for an empty
            // one; this is where that promise is cashed.
            assertThat(has("probe-a")).isTrue();
            assertThat(has("probe-b")).isTrue();
        }

        @Test
        void every_poll_writes_the_header_whatever_the_outcome() {
            land(snapshot(Outcome.complete(), "probe-a"));
            Instant afterComplete = header().recordedAt();

            land(DiscoveryResult.failed("the cluster did not answer"));

            // ADR-0086: the store now reads three ways rather than one. A FAILED header with no
            // entries is "we tried and could not look"; no header at all is "nobody has ever polled
            // this pair". Collapsing them tells an operator with a typo'd credential to wait five
            // minutes for a poll that will never succeed.
            assertThat(header().outcome()).isEqualTo(OutcomeStatus.FAILED);
            assertThat(header().recordedAt()).isAfterOrEqualTo(afterComplete);
            assertThat(has("probe-a")).isTrue();
        }

        private io.nodqora.core.store.SnapshotHeader header() {
            return snapshots.headers(ENVIRONMENT).stream()
                    .filter(candidate -> candidate.pluginId().equals("probe"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    @Nested
    class UpdatedAtMeansTheTopologyChanged {

        @Test
        void an_unchanged_node_keeps_its_timestamps_across_polls() {
            land(snapshot(Outcome.complete(), "probe-a"));
            NodeRecord first = node("probe-a");

            land(snapshot(Outcome.complete(), "probe-a"));
            land(snapshot(Outcome.complete(), "probe-a"));

            // ADR-0050's whole point. The fold rewrites every row on every poll, so the write path
            // cannot imply a change; without the diff, `updatedAt` degenerates into a poll clock
            // and ADR-0003's boundary silently stops meaning anything.
            assertThat(node("probe-a").updatedAt()).isEqualTo(first.updatedAt());
            assertThat(node("probe-a").discoveredAt()).isEqualTo(first.discoveredAt());
        }

        @Test
        void a_collection_round_tripping_through_jsonb_is_not_a_change() {
            // Numbers are the trap: a plugin emitting a long and the database handing back an int
            // compares unequal on every poll unless both sides are normalized the same way.
            DiscoveryResult withMetadata = new DiscoveryResult(
                    List.of(new DiscoveredNode(
                            "probe-a", null, null, null, null, List.of(), List.of(),
                            Map.of("retentionMs", 604800000L, "partitions", 12))),
                    List.of(),
                    List.of(),
                    List.of(),
                    Outcome.complete());

            land(withMetadata);
            Instant first = node("probe-a").updatedAt();
            land(withMetadata);

            assertThat(node("probe-a").updatedAt()).isEqualTo(first);
        }

        @Test
        void a_real_change_moves_updated_at_but_not_discovered_at() {
            land(snapshot(Outcome.complete(), "probe-a"));
            NodeRecord first = node("probe-a");

            land(new DiscoveryResult(
                    List.of(new DiscoveredNode(
                            "probe-a", "service", null, null, null, List.of(), List.of(), Map.of())),
                    List.of(),
                    List.of(),
                    List.of(),
                    Outcome.complete()));

            assertThat(node("probe-a").updatedAt()).isAfter(first.updatedAt());
            assertThat(node("probe-a").discoveredAt()).isEqualTo(first.discoveredAt());
        }

        @Test
        void a_node_that_leaves_and_returns_gets_a_new_row() {
            land(snapshot(Outcome.complete(), "probe-a"));
            long firstId = node("probe-a").id();

            land(snapshot(Outcome.complete()));
            land(snapshot(Outcome.complete(), "probe-a"));

            // ADR-0050: `discoveredAt` resets because ADR-0043 keeps no tombstone, and a new
            // surrogate id means a fresh NodeState reading UNKNOWN for up to one fast-loop interval
            // — correct rather than merely tolerable, since we genuinely have no observation of a
            // node just re-created.
            assertThat(node("probe-a").id()).isNotEqualTo(firstId);
        }
    }

    @Nested
    class StubNodes {

        @Test
        void an_edge_to_a_key_nobody_carries_materializes_a_bare_node() {
            land(new DiscoveryResult(
                    List.of(DiscoveredNode.ofKey("probe-a")),
                    List.of(new DiscoveredEdge("probe-a", "probe-ghost", "WRITES_TO")),
                    List.of(),
                    List.of(),
                    Outcome.complete()));

            // ADR-0048: a canvas edge needs two nodes, and a typo'd endpoint producing a bare ghost
            // beside the real node is ugly and obvious, where dropping the edge produces a
            // relationship that silently never existed.
            NodeRecord stub = node("probe-ghost");
            assertThat(stub.type()).isNull();
            assertThat(stub.displayName()).isNull();
            assertThat(stub.backings()).isEmpty();
            // ADR-0079: the union is uniform, so a stub is literally the empty case.
            assertThat(stub.sources()).containsExactly("probe");
        }
    }
}
