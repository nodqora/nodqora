// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.fold;

import static org.assertj.core.api.Assertions.assertThat;

import io.nodqora.core.graph.FoldedNodeState;
import io.nodqora.core.registry.PluginOrder;
import io.nodqora.plugin.api.Health;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The fast half's fold, in isolation. Composition splits three ways and each has a different rule:
 * ADR-0024 for {@code health}, registry order for {@code rawSignal}, namespaced union for
 * {@code metrics} — plus {@code min} for freshness.
 */
class StateFoldTest {

    private static final Instant EARLY = Instant.parse("2026-09-05T09:00:00Z");
    private static final Instant LATE = Instant.parse("2026-09-05T09:00:25Z");

    private final StateFold fold = new StateFold(
            new PluginOrder(List.of("yaml", "kubernetes", "kafka", "connect"), List.of("yaml", "connect", "kubernetes", "kafka")));

    private static ContributionView from(
            String plugin, Health health, String rawSignal, Map<String, Object> metrics, Instant observedAt) {
        return new ContributionView(1L, plugin, health, rawSignal, metrics, observedAt);
    }

    @Test
    void raw_signal_joins_in_registry_order_whatever_order_the_polls_landed_in() {
        // CONTEXT.md's own worked example — "3 desired / 2 ready; lag 40000" — is exactly
        // kubernetes-then-kafka, so the model already assumes this order. It is the *registry* order
        // and not precedence: precedence settles a contested scalar on the slow side, which is a
        // different question from what reads well in a sentence.
        List<FoldedNodeState> folded = fold.fold(List.of(
                from("kafka", Health.DEGRADED, "lag 40000", Map.of("maxConsumerLag", 40000), LATE),
                from("kubernetes", Health.DEGRADED, "3 desired / 2 ready", Map.of(), EARLY)));

        assertThat(folded).hasSize(1);
        assertThat(folded.getFirst().rawSignal()).isEqualTo("3 desired / 2 ready; lag 40000");
    }

    @Test
    void metrics_are_namespaced_by_the_plugin_that_reported_them() {
        List<FoldedNodeState> folded = fold.fold(List.of(
                from("kubernetes", Health.HEALTHY, "2 desired / 2 ready", Map.of("readyReplicas", 2), EARLY),
                from("kafka", Health.HEALTHY, "lag 120", Map.of("maxConsumerLag", 120), EARLY)));

        // ADR-0006: a plugin writes into its own namespace and cannot reach another's. The allow-list
        // is per plugin, so two plugins may legitimately use the same key name.
        assertThat(folded.getFirst().metrics())
                .containsOnlyKeys("kubernetes", "kafka")
                .containsEntry("kubernetes", Map.of("readyReplicas", 2));
    }

    @Test
    void freshness_is_the_stalest_contribution_and_never_the_freshest() {
        List<FoldedNodeState> folded = fold.fold(List.of(
                from("kubernetes", Health.HEALTHY, "3 desired / 3 ready", Map.of(), LATE),
                from("kafka", Health.HEALTHY, "lag 120", Map.of(), EARLY)));

        // ADR-0072: `max` would let a five-second-old kubernetes reading present a kafka reading from
        // three cycles ago as current. The frontend renders this as "how old is this", so the number
        // must never overstate — which means it is the min, always.
        assertThat(folded.getFirst().observedAt()).isEqualTo(EARLY);
    }

    @Test
    void the_collapse_is_the_one_on_the_enum_and_not_a_second_copy() {
        List<FoldedNodeState> folded = fold.fold(List.of(
                from("connect", Health.DISABLED, "PAUSED", Map.of(), EARLY),
                from("kafka", Health.DEGRADED, "lag 2100000", Map.of(), EARLY),
                from("kubernetes", Health.HEALTHY, "2 desired / 2 ready", Map.of(), EARLY)));

        assertThat(folded.getFirst().health()).isEqualTo(Health.DISABLED);
    }

    @Test
    void a_signal_nobody_supplied_is_null_rather_than_an_empty_string() {
        // ADR-0028: never a string such as "no adapter". The core would otherwise be describing
        // plugin absence, which ADR-0015 forbids it from knowing about at all.
        List<FoldedNodeState> folded =
                fold.fold(List.of(from("kubernetes", Health.HEALTHY, null, Map.of(), EARLY)));

        assertThat(folded.getFirst().rawSignal()).isNull();
        assertThat(folded.getFirst().metrics()).isEmpty();
    }

    @Test
    void a_node_every_observer_abstained_on_gets_no_row_rather_than_an_unknown_one() {
        // ADR-0104, asserted on the fold rather than on the store. The store drops UNKNOWN before it
        // is written, so this input cannot occur today — which is exactly why it is worth pinning:
        // the fold is a pure function and must be right about its own inputs, not about what
        // happened to be written upstream. A row here would read UNKNOWN *and* report an
        // `observedAt` for an observation nobody made, which is the second UNKNOWN ADR-0104 forbids.
        assertThat(fold.fold(List.of(from("kubernetes", Health.UNKNOWN, "object has gone", Map.of(), EARLY))))
                .isEmpty();
    }

    @Test
    void an_abstention_alongside_a_real_reading_is_discarded_and_does_not_drag_the_freshness() {
        // ADR-0024 step 1 removes the abstention, so it cannot win, lose, or contribute a segment to
        // `rawSignal`. It must not reach `observedAt` either: EARLY belongs to an observation that
        // did not happen, and `min` would have published it as the row's age.
        List<FoldedNodeState> folded = fold.fold(List.of(
                from("kubernetes", Health.UNKNOWN, "object has gone", Map.of(), EARLY),
                from("kafka", Health.DEGRADED, "lag 40000", Map.of("maxConsumerLag", 40000), LATE)));

        assertThat(folded).hasSize(1);
        assertThat(folded.getFirst().health()).isEqualTo(Health.DEGRADED);
        assertThat(folded.getFirst().rawSignal()).isEqualTo("lag 40000");
        assertThat(folded.getFirst().observedAt()).isEqualTo(LATE);
    }

    @Test
    void an_environment_with_no_contributions_folds_to_no_rows_at_all() {
        // Not "folds to a page of UNKNOWN rows". A node nobody observes has no row, and ADR-0028's
        // outer join synthesizes the four values on the way out — which is why /state needs no
        // branch for the six production nodes that are permanently UNKNOWN.
        assertThat(fold.fold(List.of())).isEmpty();
    }

    @Test
    void nodes_are_folded_independently_of_one_another() {
        // ADR-0027: health is local. There is no propagation step here to get wrong, and its absence
        // is why this fold needs no cycle handling despite the graph having cycles.
        List<FoldedNodeState> folded = fold.fold(List.of(
                new ContributionView(1L, "kubernetes", Health.UNHEALTHY, "3 desired / 0 ready", Map.of(), EARLY),
                new ContributionView(2L, "kubernetes", Health.HEALTHY, "3 desired / 3 ready", Map.of(), EARLY)));

        assertThat(folded).extracting(FoldedNodeState::health).containsExactly(Health.UNHEALTHY, Health.HEALTHY);
    }
}
