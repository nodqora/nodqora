// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.discovery;

import io.nodqora.core.config.BoundConfiguration;
import io.nodqora.core.config.BoundConfiguration.ConfiguredCapability;
import io.nodqora.core.fold.DiscoveryFoldRunner;
import io.nodqora.core.store.SnapshotStore;
import io.nodqora.plugin.api.DiscoveryCapability;
import io.nodqora.plugin.api.DiscoveryRequest;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.core.plugin.PluginCalls;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The slow loop: one call per {@code (plugin, environment)} pair, each returning a full stateless
 * snapshot of that plugin's scope (ADR-0012).
 *
 * <p>Pairs run in parallel on virtual threads under an engine-imposed timeout, and <b>one failing
 * pair does not touch the others</b> — a dead staging cluster must not stall production discovery. A
 * plugin that throws is recorded as {@code FAILED} rather than propagating, because ADR-0012 made
 * the value explicit precisely so a failed poll is never mistaken for an empty one, and ADR-0046
 * makes {@code FAILED} leave the store untouched.
 *
 * <p>A snapshot landing triggers the fold for its environment (ADR-0043). The two units are
 * separate: the snapshot write is atomic in itself, and the fold recomputes the whole environment
 * and commits atomically, so a reader sees the graph before or after a plugin's snapshot, never
 * mid-fold.
 */
@Component
public class DiscoveryEngine {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryEngine.class);

    private final BoundConfiguration configuration;
    private final SnapshotStore snapshots;
    private final DiscoveryFoldRunner folds;
    private final Clock clock;

    public DiscoveryEngine(
            BoundConfiguration configuration, SnapshotStore snapshots, DiscoveryFoldRunner folds, Clock clock) {
        this.configuration = configuration;
        this.snapshots = snapshots;
        this.folds = folds;
        this.clock = clock;
    }

    /** One full pass over every configured pair. Also the seam the tests drive. */
    public void pollEveryEnvironment() {
        configuration.environments().forEach(environment -> poll(environment.key()));
    }

    public void poll(String environmentKey) {
        List<ConfiguredCapability> pairs = configuration.discoveryPairs(environmentKey);
        if (pairs.isEmpty()) {
            return;
        }

        // Strictly below the interval, so a hung plugin cannot overlap its own next run.
        Duration timeout = configuration.refresh().discovery().dividedBy(2);
        PluginCalls.inParallel(pairs.stream()
                .map(pair -> (Runnable) () -> recordOnePair(pair, timeout))
                .toList());

        folds.run(environmentKey);
    }

    private void recordOnePair(ConfiguredCapability pair, Duration timeout) {
        String pluginId = pair.plugin().id();
        DiscoveryResult result = invoke(pair, timeout);
        snapshots.record(pair.environmentKey(), pluginId, result, clock.instant());
        log.debug(
                "discovery {}/{} -> {} ({} nodes, {} edges)",
                pair.environmentKey(),
                pluginId,
                result.outcome().status(),
                result.nodes().size(),
                result.edges().size());
    }

    @SuppressWarnings("unchecked")
    private DiscoveryResult invoke(ConfiguredCapability pair, Duration timeout) {
        DiscoveryCapability<Object> capability = (DiscoveryCapability<Object>) pair.plugin();
        return PluginCalls.within(
                timeout,
                "discovery",
                () -> capability.discover(new DiscoveryRequest<>(pair.environmentKey(), pair.config())),
                cause -> failed(pair, cause));
    }

    private DiscoveryResult failed(ConfiguredCapability pair, String cause) {
        log.warn("discovery {}/{} failed: {}", pair.environmentKey(), pair.plugin().id(), cause);
        return DiscoveryResult.failed(cause);
    }
}
