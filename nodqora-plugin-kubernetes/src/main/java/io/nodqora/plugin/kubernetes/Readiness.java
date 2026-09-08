// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

import io.nodqora.plugin.api.Health;

/**
 * One backing's readiness, normalized — the single shape {@link KubernetesHealth} collapses, phrases
 * and sums, whatever it was read from (ADR-0148).
 *
 * <p>Three kinds of object reach this record and none of them is quite like the others: a Deployment
 * or StatefulSet has {@code spec.replicas} and {@code status.readyReplicas}, a CronJob has neither
 * and only {@code spec.suspend}, and a {@code pods} selector has a live count and a ready count but
 * no spec at all. Normalizing once is what keeps ADR-0024's collapse, ADR-0105's naming and
 * ADR-0028's two metric keys as one rule each rather than one rule per kind — the shape where the
 * third kind is the one somebody forgets to add.
 *
 * <p>{@code desired} and {@code ready} are {@code null} together for anything with no replica
 * concept, and ADR-0105 then keeps it out of both sums: a zero there would render on the canvas as a
 * scaled-down workload, which is the one reading it must not have.
 *
 * @param reference the backing reference this was read through, and ADR-0105's label in the plural
 *     case
 * @param health this backing's vote, {@code UNKNOWN} when it abstains (ADR-0104)
 * @param phrase the {@code rawSignal} fragment — {@code "3 desired / 2 ready"}, {@code "2 pods / 2
 *     ready"}, {@code "suspended"}
 */
record Readiness(String reference, Health health, String phrase, Integer desired, Integer ready) {

    /**
     * A backing this plugin found and has no vote about. It still carries a phrase: ADR-0024
     * discards the vote, but the object was <em>seen</em>, and a node whose other backing is
     * {@code HEALTHY} should still read {@code "…/nightly-reconcile scheduled"} beside it rather
     * than silently omitting the half that had nothing to say.
     */
    static Readiness abstains(String reference, String phrase) {
        return new Readiness(reference, Health.UNKNOWN, phrase, null, null);
    }

    /**
     * ADR-0025's arithmetic, over any pair of counts: all ready is {@code HEALTHY}, some is
     * {@code DEGRADED}, none is {@code UNHEALTHY}. More ready than desired is a scale-down in
     * progress and reads {@code HEALTHY} — "all desired are ready" is true, and there is nothing to
     * report.
     *
     * <p>Only called with {@code desired > 0}. Nought of nought ready is both "all of them" and
     * "none of them", so ADR-0025 applies this rule only above zero and each caller decides what
     * zero means for the thing it read — declared intent for a workload, an abstention for a pod set.
     */
    static Health arithmetic(int desired, int ready) {
        if (ready >= desired) {
            return Health.HEALTHY;
        }
        return ready == 0 ? Health.UNHEALTHY : Health.DEGRADED;
    }
}
