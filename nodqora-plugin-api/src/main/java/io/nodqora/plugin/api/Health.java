// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.api;

import java.util.Collection;

/**
 * The normalized operational state of a Node — the one closed vocabulary in a model of open
 * strings (ADR-0003, ADR-0024).
 *
 * <p>The five values are not a ladder. {@code UNKNOWN} is an abstention and {@code DISABLED} is
 * judgement suspended; only {@code HEALTHY < DEGRADED < UNHEALTHY} is a severity order.
 */
public enum Health {
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
    UNKNOWN,
    DISABLED;

    /**
     * ADR-0024's three steps, and <b>the only composition algorithm in the system</b>.
     *
     * <ol>
     *   <li><b>Discard</b> every {@code UNKNOWN}. If nothing remains — including the case where no
     *       plugin was asked — the answer is {@code UNKNOWN}.
     *   <li>If any survivor is {@code DISABLED}, the answer is {@code DISABLED}, <b>outright</b>,
     *       including over {@code UNHEALTHY}. A paused connector on a crash-looping workload is,
     *       to the operator asking "does anyone need to act on this?", still paused.
     *   <li>Otherwise worst-wins over the only real ladder: {@code HEALTHY < DEGRADED < UNHEALTHY}.
     * </ol>
     *
     * <p>It is <em>not</em> worst-wins. Under the incident scenario {@code payments-es-sink} is
     * observed by three plugins at once — {@code connect} says {@code DISABLED}, {@code kafka} says
     * {@code DEGRADED} because the lag behind a paused sink is growing, {@code kubernetes} says
     * {@code HEALTHY} because the StatefulSet hosting it is 2/2 — and the answer is
     * {@code DISABLED}. On any severity ladder where {@code DEGRADED} outranks {@code DISABLED},
     * {@code max()} returns the wrong one.
     *
     * <p>It lives here, on the enum, rather than in the state engine because ADR-0024 applies it at
     * <b>two levels</b>: a plugin observing several backings of one node collapses them before
     * returning one contribution, and the state engine collapses one contribution per plugin into
     * the row. A second copy in the core would be the per-plugin precedence table ADR-0024 rejected,
     * arrived at by drift rather than by decision. Putting it on the enum also makes ADR-0024's
     * closing line literal: a sixth value would cost a glyph <em>and</em> a position in this method.
     *
     * <p>There is deliberately no plugin argument. The rule never asks <em>who</em> said something,
     * because a node's health must not change when an operator merely configures one more plugin.
     */
    public static Health collapse(Collection<Health> contributions) {
        boolean disabled = false;
        Health worst = null;
        for (Health contribution : contributions) {
            // Step 1. An abstention is not a finding about anything, so it is removed before the
            // others are weighed rather than losing to them.
            if (contribution == UNKNOWN) {
                continue;
            }
            if (contribution == DISABLED) {
                disabled = true;
            } else if (worst == null || contribution.severity() > worst.severity()) {
                worst = contribution;
            }
        }
        // Step 2 before step 3: DISABLED is judgement suspended, so it does not compete on severity.
        if (disabled) {
            return DISABLED;
        }
        return worst == null ? UNKNOWN : worst;
    }

    /** The one real ladder. {@code UNKNOWN} and {@code DISABLED} never reach it. */
    private int severity() {
        return switch (this) {
            case HEALTHY -> 0;
            case DEGRADED -> 1;
            case UNHEALTHY -> 2;
            case UNKNOWN, DISABLED -> throw new IllegalStateException(
                    this + " is not a severity; ADR-0024 removes it before step 3");
        };
    }
}
