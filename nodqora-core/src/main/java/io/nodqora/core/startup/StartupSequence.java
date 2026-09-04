package io.nodqora.core.startup;

import io.nodqora.core.config.BoundConfiguration;
import io.nodqora.core.discovery.DiscoveryLoop;
import io.nodqora.core.fold.DiscoveryFoldRunner;
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
 */
@Component
public class StartupSequence implements ApplicationRunner {

    private final ConfigMirrorReconciler reconciler;
    private final BoundConfiguration configuration;
    private final DiscoveryFoldRunner folds;
    private final DiscoveryLoop loop;

    public StartupSequence(
            ConfigMirrorReconciler reconciler,
            BoundConfiguration configuration,
            DiscoveryFoldRunner folds,
            DiscoveryLoop loop) {
        this.reconciler = reconciler;
        this.configuration = configuration;
        this.folds = folds;
        this.loop = loop;
    }

    @Override
    public void run(ApplicationArguments args) {
        reconciler.reconcile();
        configuration.environments().forEach(environment -> folds.run(environment.key()));
        loop.start();
    }
}
