package io.nodqora.core.startup;

import io.nodqora.core.config.BoundConfiguration;
import io.nodqora.core.discovery.DiscoveryLoop;
import io.nodqora.core.fold.DiscoveryFoldRunner;
import io.nodqora.core.fold.StateFoldRunner;
import io.nodqora.core.health.HealthLoop;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Reconcile, fold, then poll.
 *
 * <p>The middle step is the one worth naming: <b>there is no special cold-start path</b> (ADR-0043).
 * On boot the snapshot store already holds the last accepted snapshot per pair, so the fold runs from
 * it immediately and the first incoming snapshot updates its own slot exactly as in steady state.
 * Durability is what makes that safe — with an in-memory store and only the folded rows persisted, a
 * restart in which one plugin polls first would fold over a store holding one snapshot and delete
 * most of the graph, restoring it minutes later. It is also what repopulates the derived tables after
 * ADR-0080 drops them in a migration: the boot fold is the repopulation step, with no marker and no
 * manual trigger.
 *
 * <p>Both halves boot the same way. The contribution store is durable too (ADR-0072), so the state
 * fold also runs from what is already there rather than from a cold-start path — a restart mid-cadence
 * republishes the last observations instead of painting the whole graph grey for thirty seconds.
 * The folds run before either loop starts, so the first health pass is routed by a graph that exists.
 */
@Component
public class StartupSequence implements ApplicationRunner {

    private final ConfigMirrorReconciler reconciler;
    private final BoundConfiguration configuration;
    private final DiscoveryFoldRunner graphFolds;
    private final StateFoldRunner stateFolds;
    private final DiscoveryLoop discovery;
    private final HealthLoop health;

    public StartupSequence(
            ConfigMirrorReconciler reconciler,
            BoundConfiguration configuration,
            DiscoveryFoldRunner graphFolds,
            StateFoldRunner stateFolds,
            DiscoveryLoop discovery,
            HealthLoop health) {
        this.reconciler = reconciler;
        this.configuration = configuration;
        this.graphFolds = graphFolds;
        this.stateFolds = stateFolds;
        this.discovery = discovery;
        this.health = health;
    }

    @Override
    public void run(ApplicationArguments args) {
        reconciler.reconcile();
        configuration.environments().forEach(environment -> {
            // Slow half first: the state fold reads `node.id`, so it needs the rows this creates.
            graphFolds.run(environment.key());
            stateFolds.run(environment.key());
        });
        if (configuration.refresh().autostart()) {
            discovery.start();
            health.start();
        }
    }
}
