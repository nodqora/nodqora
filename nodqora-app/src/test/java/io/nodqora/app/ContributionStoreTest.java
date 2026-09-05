package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.nodqora.core.fold.StateFoldRunner;
import io.nodqora.core.store.HealthStore;
import io.nodqora.plugin.api.Health;
import io.nodqora.plugin.api.HealthCapability.HealthResult;
import io.nodqora.plugin.api.Outcome;
import io.nodqora.plugin.api.StateContribution;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The fast half is a <b>store plus a fold</b>, not an in-place merge (ADR-0072) — and the slice's
 * first done-when is that in-place is <em>structurally impossible</em> rather than merely
 * discouraged.
 *
 * <p>Two independent arguments make it so, and both are exercised here:
 *
 * <ul>
 *   <li><b>You cannot recompute a collapse from its own output.</b> ADR-0024 discards abstentions
 *       before collapsing, so the composed row has already thrown away what a re-collapse would
 *       need. A row reading HEALTHY could be one observer or ten, with any number of abstentions
 *       among them; there is no way back.
 *   <li><b>A plugin could never stop saying {@code DISABLED}.</b> It is emitted from
 *       {@code spec.replicas: 0}, so scaling back up produces a <em>different</em> contribution
 *       rather than a retraction — and merging that into a row that already says DISABLED would need
 *       the old verdict found and cleared by hand.
 * </ul>
 *
 * <p>These drive {@link HealthStore} directly rather than through a plugin, because the properties
 * are the store's and must hold for the three plugins that do not exist yet.
 */
class ContributionStoreTest extends NodqoraIntegrationTest {

    private static final String PLUGIN = "kubernetes";
    private static final String NODE = "payments-enricher";

    @Autowired
    HealthStore contributions;

    @Autowired
    StateFoldRunner folds;

    @Test
    void a_disabled_verdict_is_replaced_rather_than_retracted_when_the_workload_scales_back_up() {
        record(Health.DISABLED, "scaled to 0", Outcome.complete());
        assertThat(health(NODE)).isEqualTo("DISABLED");

        // The load-bearing half. Under an in-place merge this would have to *unset* DISABLED, which
        // ADR-0024 gives no operation for — DISABLED wins outright, so a composed row carrying it can
        // never be argued down by a new contribution. Replacing the contribution and recomputing has
        // no such problem, and needed no rule to say so.
        record(Health.HEALTHY, "3 desired / 3 ready", Outcome.complete());
        assertThat(health(NODE)).isEqualTo("HEALTHY");
    }

    @Test
    void a_failed_run_leaves_the_last_reading_standing_rather_than_blanking_it() {
        record(Health.DEGRADED, "3 desired / 2 ready", Outcome.complete());

        // ADR-0046 on the fast half: FAILED changes nothing. ADR-0026's posture is that a plugin
        // which could not look must not be reported as a finding about the node — so the last good
        // reading stands and goes visibly stale through `observedAt`, and the failure surfaces in the
        // outcome block instead. Blanking to UNKNOWN would deliver "we could not look" as "nothing
        // is wrong here".
        contributions.record(
                "production", PLUGIN, new HealthResult(Map.of(), Outcome.failed("cluster unreachable")), now());
        folds.run("production");

        assertThat(health(NODE)).isEqualTo("DEGRADED");
        assertThat(outcome()).isEqualTo("FAILED");
    }

    @Test
    void a_complete_run_that_stops_carrying_a_node_deletes_its_contribution() {
        record(Health.DEGRADED, "3 desired / 2 ready", Outcome.complete());
        assertThat(health(NODE)).isEqualTo("DEGRADED");

        // ADR-0047, on the fast half: absence from a COMPLETE run deletes immediately. The plugin was
        // asked and did not answer for this node, which is the same event as the node leaving its
        // scope. Deletion is non-destructive and self-healing within one thirty-second cadence, while
        // retention would leave a stale verdict standing with nothing to reveal it.
        contributions.record("production", PLUGIN, new HealthResult(Map.of(), Outcome.complete()), now());
        folds.run("production");

        assertThat(health(NODE)).isEqualTo("UNKNOWN");
        assertThat(rawSignalIsNull(NODE)).isTrue();
    }

    @Test
    void a_partial_run_retains_what_it_did_not_carry() {
        record(Health.DEGRADED, "3 desired / 2 ready", Outcome.complete());

        // The difference between PARTIAL and COMPLETE is exactly the deletion, which is why ADR-0026
        // gave health the same three outcomes rather than a boolean: a plugin that looked at half its
        // scope must not delete the other half.
        contributions.record(
                "production",
                PLUGIN,
                new HealthResult(Map.of(), Outcome.partial("one namespace could not be listed")),
                now());
        folds.run("production");

        assertThat(health(NODE)).isEqualTo("DEGRADED");
        assertThat(outcome()).isEqualTo("PARTIAL");
    }

    @Test
    void an_abstention_never_reaches_the_store_and_so_leaves_no_row() {
        record(Health.DEGRADED, "3 desired / 2 ready", Outcome.complete());

        // ADR-0104: an abstention is an omission. Storing UNKNOWN would give the fast half two ways
        // to be UNKNOWN that disagree — one where the composed row does not exist, one where it
        // exists carrying an `observedAt` for an observation nobody made — and ADR-0024 discards it
        // one step later regardless.
        record(Health.UNKNOWN, "could not read", Outcome.complete());

        assertThat(health(NODE)).isEqualTo("UNKNOWN");
        assertThat(rawSignalIsNull(NODE)).isTrue();
        assertThat(storedContributions()).isZero();
    }

    @Test
    void a_contribution_for_a_node_the_graph_does_not_carry_is_dropped_and_not_an_error() {
        // ADR-0050 and ADR-0075: written as INSERT … SELECT … FROM node WHERE …, so a node deleted by
        // a concurrent discovery fold yields zero rows rather than a foreign key violation. The
        // health loop runs ten times per discovery cadence, so this race is ordinary rather than
        // exotic.
        contributions.record(
                "production",
                PLUGIN,
                new HealthResult(
                        Map.of("a-node-that-left", new StateContribution(Health.HEALTHY, "1 desired / 1 ready", Map.of())),
                        Outcome.complete()),
                now());
        folds.run("production");

        assertThat(storedContributions()).isZero();
    }

    @Test
    void the_key_is_matched_case_folded_like_every_other_node_key() {
        // ADR-0020: a plugin returns the key it resolved, and nothing guarantees it is spelled the
        // way the fold happened to store it. Matching verbatim would drop the contribution silently,
        // which reads as "nothing observes this node" — indistinguishable from the truth.
        contributions.record(
                "production",
                PLUGIN,
                new HealthResult(
                        Map.of("Payments-Enricher", new StateContribution(Health.HEALTHY, "3 desired / 3 ready", Map.of())),
                        Outcome.complete()),
                now());
        folds.run("production");

        assertThat(health(NODE)).isEqualTo("HEALTHY");
    }

    // ---------------------------------------------------------------- helpers

    private void record(Health health, String rawSignal, Outcome outcome) {
        contributions.record(
                "production",
                PLUGIN,
                new HealthResult(Map.of(NODE, new StateContribution(health, rawSignal, Map.of())), outcome),
                now());
        folds.run("production");
    }

    private static Instant now() {
        return Instant.parse("2026-09-05T09:00:00Z");
    }

    /** Read off {@code node_state} rather than the endpoint: these are properties of the store. */
    private String health(String nodeKey) {
        return jdbc.queryForObject(
                """
                select coalesce(s.health, 'UNKNOWN') from node n
                left join node_state s on s.node_id = n.id
                where n.environment_key = 'production' and lower(btrim(n.key)) = ?
                """,
                String.class,
                nodeKey);
    }

    private boolean rawSignalIsNull(String nodeKey) {
        return jdbc.queryForObject(
                """
                select s.raw_signal is null from node n
                left join node_state s on s.node_id = n.id
                where n.environment_key = 'production' and lower(btrim(n.key)) = ?
                """,
                Boolean.class,
                nodeKey);
    }

    /**
     * Scoped to production, because staging polls the same recording and holds contributions of its
     * own. ADR-0004 is a scope and not a filter, and a count that spanned both would pass or fail on
     * the other environment's data.
     */
    private int storedContributions() {
        return jdbc.queryForObject(
                """
                select count(*) from health_contribution c
                join node n on n.id = c.node_id
                where n.environment_key = 'production'
                """,
                Integer.class);
    }

    private String outcome() {
        return jdbc.queryForObject(
                "select outcome from health_run where environment_key = 'production' and plugin_id = ?",
                String.class,
                PLUGIN);
    }
}
