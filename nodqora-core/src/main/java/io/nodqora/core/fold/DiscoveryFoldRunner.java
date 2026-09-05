// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.fold;

import io.nodqora.core.graph.FoldedGraph;
import io.nodqora.core.store.GraphStore;
import io.nodqora.core.store.SnapshotStore;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the discovery fold for one environment: lock, read the store, recompute, commit (ADR-0043).
 *
 * <p>The whole environment is recomputed rather than the keys a snapshot touched. A key-scoped path
 * needs a correct affected-key set <em>including the keys a snapshot stopped carrying</em>, and
 * getting it wrong yields a graph that silently no longer equals its own inputs.
 *
 * <p>The first statement takes the environment row (ADR-0075), held to commit. Order-independence is
 * a property of the fold's <em>inputs</em>, not of its commits: two unserialized folds can each be
 * correct and still let the later-committing, earlier-reading one erase a whole plugin's nodes for a
 * cadence — which under ADR-0004 renders as drift, a legitimate reading of the data and therefore
 * the hardest class of bug this system can produce. Holding the lock across the read is the fix; a
 * lock taken only at write time would not prevent a stale read.
 */
@Component
public class DiscoveryFoldRunner {

    private final JdbcTemplate jdbc;
    private final SnapshotStore snapshots;
    private final GraphStore graph;
    private final GraphFold fold;
    private final Clock clock;

    public DiscoveryFoldRunner(
            JdbcTemplate jdbc, SnapshotStore snapshots, GraphStore graph, GraphFold fold, Clock clock) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.graph = graph;
        this.fold = fold;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void run(String environmentKey) {
        List<Integer> locked = jdbc.queryForList(
                "select 1 from environment where key = ? for update", Integer.class, environmentKey);
        if (locked.isEmpty()) {
            // The environment left the config between the poll and the fold; ADR-0074's cascade has
            // already taken its store with it, so there is nothing to recompute.
            return;
        }
        FoldedGraph folded = fold.fold(snapshots.read(environmentKey));
        graph.write(environmentKey, folded, clock.instant());
    }
}
