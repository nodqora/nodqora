// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.api;

import static io.nodqora.plugin.api.Health.DEGRADED;
import static io.nodqora.plugin.api.Health.DISABLED;
import static io.nodqora.plugin.api.Health.HEALTHY;
import static io.nodqora.plugin.api.Health.UNHEALTHY;
import static io.nodqora.plugin.api.Health.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-0024, the slice's one hard idea. The rule is <b>not</b> worst-wins, and every test here is a
 * case where the obvious default gives the wrong answer.
 */
class HealthCollapseTest {

    @Test
    void the_case_that_defeats_plain_worst_wins() {
        // The fixture's incident `payments-es-sink`, observed by three plugins at once: `connect`
        // sees the connector PAUSED, `kafka` sees lag piling up behind it, `kubernetes` sees the
        // StatefulSet hosting it 2/2 ready. The fixture says DISABLED. On any severity ladder where
        // DEGRADED outranks DISABLED, max() says DEGRADED — and the operator is paged for a sink
        // somebody paused on purpose.
        assertEquals(DISABLED, Health.collapse(List.of(DISABLED, DEGRADED, HEALTHY)));
    }

    @Test
    void disabled_wins_outright_including_over_unhealthy() {
        // Not "DISABLED is the most severe". It is judgement suspended: the workload underneath a
        // paused connector may well be crash-looping, and that is a *consequence* of the pause
        // rather than a finding about the node. To the operator asking "does anyone need to act on
        // this?", it is still paused.
        assertEquals(DISABLED, Health.collapse(List.of(UNHEALTHY, DISABLED)));
        assertEquals(DISABLED, Health.collapse(List.of(DISABLED, UNHEALTHY)));
    }

    @Test
    void an_abstention_is_discarded_rather_than_losing() {
        // Step 1, and the reason the order of the three steps matters. If UNKNOWN merely lost, a
        // node observed by one plugin that answered and three that were not asked would still read
        // as whatever the ladder said about four values.
        assertEquals(HEALTHY, Health.collapse(List.of(UNKNOWN, HEALTHY, UNKNOWN)));
        assertEquals(DISABLED, Health.collapse(List.of(UNKNOWN, DISABLED)));
    }

    @Test
    void nothing_surviving_is_unknown_and_so_is_nothing_at_all() {
        // ADR-0013's "a node with no backings is UNKNOWN" stays arithmetic — step 1 with an empty
        // input, not a rule anyone had to write. Four of the fixture's ten production nodes rest
        // here permanently, and that is normal rather than an error state.
        assertEquals(UNKNOWN, Health.collapse(List.of()));
        assertEquals(UNKNOWN, Health.collapse(List.of(UNKNOWN, UNKNOWN)));
    }

    @Test
    void the_survivors_collapse_worst_wins_over_the_one_real_ladder() {
        assertEquals(HEALTHY, Health.collapse(List.of(HEALTHY, HEALTHY)));
        assertEquals(DEGRADED, Health.collapse(List.of(HEALTHY, DEGRADED)));
        assertEquals(UNHEALTHY, Health.collapse(List.of(DEGRADED, UNHEALTHY, HEALTHY)));
    }

    @Test
    void the_answer_does_not_depend_on_the_order_the_contributions_arrived_in() {
        // There is no per-plugin precedence table, and this is what its absence buys: the answer is
        // a function of what is true, not of which plugin an operator happened to configure first or
        // which poll landed first.
        assertEquals(
                Health.collapse(List.of(HEALTHY, DEGRADED, DISABLED, UNKNOWN)),
                Health.collapse(List.of(UNKNOWN, DISABLED, DEGRADED, HEALTHY)));
        assertEquals(
                Health.collapse(List.of(UNHEALTHY, HEALTHY)), Health.collapse(List.of(HEALTHY, UNHEALTHY)));
    }

    @Test
    void it_is_idempotent_on_a_single_value_but_not_reversible_from_one() {
        // Collapsing one contribution returns it, which is what lets the same algorithm run inside a
        // plugin and across plugins. The reverse does not hold, and that is ADR-0072's whole
        // argument for a contribution store: HEALTHY here could have come from one HEALTHY or from
        // ten HEALTHYs and any number of abstentions, so a composed row cannot be re-collapsed
        // against a new contribution.
        for (Health health : Health.values()) {
            assertEquals(health, Health.collapse(List.of(health)));
        }
    }
}
