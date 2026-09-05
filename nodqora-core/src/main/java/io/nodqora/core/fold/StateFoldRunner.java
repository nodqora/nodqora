package io.nodqora.core.fold;

import io.nodqora.core.graph.FoldedNodeState;
import io.nodqora.core.store.HealthStore;
import io.nodqora.core.store.NodeStateStore;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the state fold for one environment: lock, read the contributions, recompute, commit — the
 * fast half's {@link DiscoveryFoldRunner}, for the same reason and with one deliberate difference.
 *
 * <p><b>The lock is an advisory lock in its own keyspace, not the environment row</b> (ADR-0075).
 * Both folds must serialize against themselves, because order-independence is a property of a
 * fold's <em>inputs</em> and not of its commits: two unserialized folds can each be correct and
 * still let the later-committing, earlier-reading one publish a stale answer. But they must not
 * serialize against <em>each other</em>. A thirty-second loop taking {@code FOR UPDATE} on the
 * environment row would block behind a five-minute discovery fold — and worse, would make the fast
 * half's latency a function of how slow the slow half happens to be that cycle, which is exactly the
 * coupling ADR-0003's wall exists to prevent.
 *
 * <p>The whole environment is recomputed rather than the nodes one run touched, for
 * {@link DiscoveryFoldRunner}'s reason: a key-scoped path needs a correct affected-key set
 * <em>including the nodes a run stopped carrying</em>, and getting it wrong yields a fast half that
 * silently no longer equals its own inputs.
 */
@Component
public class StateFoldRunner {

    private final JdbcTemplate jdbc;
    private final HealthStore contributions;
    private final NodeStateStore states;
    private final StateFold fold;

    public StateFoldRunner(
            JdbcTemplate jdbc, HealthStore contributions, NodeStateStore states, StateFold fold) {
        this.jdbc = jdbc;
        this.contributions = contributions;
        this.states = states;
        this.fold = fold;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void run(String environmentKey) {
        // Held to commit, like the discovery fold's row lock — a lock taken only at write time would
        // not prevent the stale read, which is the failure being defended against.
        jdbc.query(
                "select pg_advisory_xact_lock(hashtext(?), hashtext('nodqora-state'))",
                rs -> null,
                environmentKey);

        List<FoldedNodeState> folded = fold.fold(contributions.read(environmentKey));
        states.write(environmentKey, folded);
    }
}
