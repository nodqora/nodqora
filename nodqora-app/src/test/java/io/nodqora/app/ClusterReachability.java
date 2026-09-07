// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Which recorded clusters can currently be reached.
 *
 * <p><b>Reachability is a second axis beside the §8 scenario, and the two are different kinds of
 * fact.</b> A scenario says what the <em>pipeline</em> is doing — a crash-looping workload, the lag
 * behind it, the sinks somebody paused — which is why one property drives all three recordings. A
 * plugin outage is not a pipeline state at all: it says how well we could look. That is ADR-0026's
 * split — <em>health says what we found; outcome says how well we looked</em> — arriving in the test
 * harness, and it is why this is not a scenario name.
 *
 * <p><b>It is mutable at runtime rather than bound from a property, and that is the whole point.</b>
 * ADR-0043 makes the store durable: <em>"on boot it already holds the last accepted snapshot per
 * pair"</em>. A cluster that has been dark since before the first poll therefore does not reproduce
 * ADR-0083's {@code blind} fixture — with no snapshot, the connectors have no backings, so the
 * health loop routes nothing to {@code connect} and its poll completes vacuously with nothing to
 * observe. The fixture's shape needs a cluster that <em>was</em> reachable and then went dark, which
 * is also the only shape an operator ever meets.
 */
public class ClusterReachability {

    private final Set<String> dark = new CopyOnWriteArraySet<>();

    /** The cluster stops answering, as of the next poll. */
    public void cut(String pluginId) {
        dark.add(pluginId);
    }

    public boolean canReach(String pluginId) {
        return !dark.contains(pluginId);
    }

    /** Every cluster answers again. Called before each test, so an outage cannot leak across one. */
    public void restoreAll() {
        dark.clear();
    }
}
