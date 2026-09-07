// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.nodqora.core.fold.DiscoveryFoldRunner;
import io.nodqora.core.fold.StateFoldRunner;
import io.nodqora.core.store.SnapshotStore;
import io.nodqora.plugin.api.DiscoveredNode;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.Outcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * <b>An environment nobody has polled says so, rather than showing an empty canvas.</b>
 *
 * <p>ADR-0056 called the outcome block a projection <em>"computed at read time from the snapshot
 * store"</em>. Read literally, a {@code (plugin, environment)} pair that has never reported has no
 * row in the store and therefore no entry in {@code plugins[]} — so a cold environment would be
 * byte-indistinguishable from an empty one, ADR-0081's chip would read {@code 0 plugins}, and
 * ADR-0087 would have nothing to select its three states with.
 *
 * <p>ADR-0085 fixed it with a fact that already existed: ADR-0074 reconciles {@code environment} and
 * {@code plugin} from the bound config at startup, so <b>the set of pairs that ought to have
 * reported is known independently of the store.</b> {@code plugins[]} is a <b>config roster
 * left-joined with the store</b>, and an unreported pair ships {@code outcome: null} and
 * {@code recordedAt: null}.
 *
 * <p>Reaching this state is one statement: truncate the config mirrors and reconcile, which is
 * exactly what a fresh deployment does, and ADR-0074's cascade is what makes one statement enough.
 * The base class does the same thing and then polls; this class stops before the poll.
 */
class ColdStoreTest extends NodqoraIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    SnapshotStore snapshots;

    @Autowired
    DiscoveryFoldRunner discoveryFolds;

    @Autowired
    StateFoldRunner stateFolds;

    /** Runs after the base class has polled, and puts the store back to never-polled. */
    @BeforeEach
    void nobodyHasPolledYet() {
        jdbc.execute("truncate table environment, plugin cascade");
        reconciler.reconcile();
    }

    private JsonNode graph() {
        return http.getForObject("/api/environments/production/graph", JsonNode.class);
    }

    private JsonNode state() {
        return http.getForObject("/api/environments/production/state", JsonNode.class);
    }

    private static List<String> outcomes(JsonNode document) {
        List<String> outcomes = new ArrayList<>();
        document.get("plugins")
                .forEach(pair -> outcomes.add(pair.get("outcome").isNull() ? "null" : pair.get("outcome").asText()));
        return outcomes;
    }

    @Test
    void the_roster_is_complete_and_every_pair_reads_null() {
        JsonNode graph = graph();

        // Four configured discovery pairs, none of which has ever reported. The entries exist
        // because the roster is config; the nulls exist because the store is empty.
        assertThat(graph.get("nodes")).isEmpty();
        assertThat(graph.get("edges")).isEmpty();
        assertThat(outcomes(graph)).containsExactly("null", "null", "null", "null");
        graph.get("plugins").forEach(pair -> {
            assertThat(pair.get("recordedAt").isNull()).isTrue();
            // ADR-0085 pins `outcome` and `recordedAt` to null and stops there. `reasons` is the
            // empty list rather than null, because there is no cause to report — a null there would
            // be a second way to say the same thing, which is the sibling field ADR-0085 rejected.
            assertThat(pair.get("reasons")).isEmpty();
        });
    }

    @Test
    void the_health_roster_is_cold_in_the_same_way_and_on_its_own_document() {
        JsonNode state = state();

        // Three pairs, not four: `yaml` declares no Health capability (ADR-0010), so it is configured
        // for one capability and not the other. The roster is per (plugin, capability), which is why
        // the two documents carry different lengths and neither is a subset of a shared block.
        assertThat(state.get("nodes")).isEmpty();
        assertThat(state.get("observedAt").isNull()).isTrue();
        assertThat(outcomes(state)).containsExactly("null", "null", "null");
    }

    @Test
    void the_denominator_is_a_config_count_and_never_shrinks_because_nothing_reported() {
        // ADR-0089's summary is only honest because of this. If `plugins[]` were a store projection
        // the chip would read `0 plugins` on a cold environment — a fresh deployment presented as an
        // unconfigured one, which is ADR-0087's first empty state told about the wrong situation.
        assertThat(graph().get("plugins")).hasSize(4);
        assertThat(state().get("plugins")).hasSize(3);
    }

    @Test
    void a_pair_that_has_reported_stands_beside_three_that_have_not() {
        // The mixed roster ADR-0089 calls "recovery in progress": the plugins repopulate
        // independently, so the chip fills in progressively rather than flipping in one step. This is
        // also the state ADR-0088's banner exists for — with `yaml` alone reporting, the canvas draws
        // a plausible pipeline while everything the other three would have added is silently missing,
        // and a node that is not there cannot be marked.
        jdbc.update("insert into plugin (id) values ('yaml') on conflict do nothing");
        snapshots.record(
                "production",
                "yaml",
                new DiscoveryResult(
                        List.of(DiscoveredNode.ofKey("payments-api")),
                        List.of(),
                        List.of(),
                        List.of(),
                        Outcome.complete()),
                Instant.now());
        discoveryFolds.run("production");

        JsonNode graph = graph();

        assertThat(graph.get("nodes")).hasSize(1);
        // Still four entries, still in registry order, and exactly one of them non-null.
        assertThat(outcomes(graph)).containsExactly("COMPLETE", "null", "null", "null");
    }

    @Test
    void a_payload_version_discard_is_indistinguishable_from_a_fresh_install() {
        JsonNode freshInstall = Golden.normalize(graph());
        JsonNode freshState = Golden.normalize(state());

        // Populate it properly, then bump every row past the current payload version and restart —
        // which is ADR-0080's discard, and which `ConfigMirrorReconciler` performs on the way in.
        discovery.pollEveryEnvironment();
        health.observeEveryEnvironment();
        assertThat(graph().get("nodes")).hasSize(10);

        for (String table : List.of("plugin_snapshot", "plugin_snapshot_entry", "health_run", "health_contribution")) {
            jdbc.update("update " + table + " set payload_version = payload_version - 1");
        }
        reconciler.reconcile();
        discoveryFolds.run("production");
        stateFolds.run("production");

        // ADR-0087: nothing distinguishes them and nothing tries to. Distinguishing them would need a
        // marker that **survives its own version bump** — a row whose shape must stay readable across
        // every future version, which is "tolerant deserialization forever", rejected by ADR-0080 for
        // having no forcing function and smuggled back as one field. The remedy is identical in both
        // cases — wait one cadence — so the distinction would change no action.
        assertThat(Golden.normalize(graph())).isEqualTo(freshInstall);
        assertThat(Golden.normalize(state())).isEqualTo(freshState);
    }
}
