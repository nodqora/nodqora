package io.nodqora.core.health;

import io.nodqora.core.config.BoundConfiguration;
import io.nodqora.core.config.BoundConfiguration.ConfiguredCapability;
import io.nodqora.core.fold.StateFoldRunner;
import io.nodqora.core.store.GraphStore;
import io.nodqora.core.store.HealthStore;
import io.nodqora.plugin.api.HealthCapability;
import io.nodqora.plugin.api.HealthCapability.HealthRequest;
import io.nodqora.plugin.api.HealthCapability.HealthResult;
import io.nodqora.plugin.api.HealthCapability.ObservableNode;
import io.nodqora.core.plugin.PluginCalls;
import io.nodqora.plugin.api.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The fast loop: one call per {@code (plugin, environment)} pair, each handed the nodes it can
 * actually see and returning one contribution per node it observed (ADR-0013, ADR-0026).
 *
 * <p><b>Backings are the routing table</b>, and that is the whole of the routing rule: a plugin is
 * asked about exactly the nodes carrying at least one {@code Backing} whose {@code plugin} is its
 * own. {@code Backing.plugin} names the <em>technology domain</em> the object belongs to rather than
 * the plugin that discovered it (ADR-0022) — which is the whole reason routing works at all, because
 * the plugin that can read a given signal is routinely not the one that could say whose signal it
 * is. One discovers the association and stamps it; the other is handed the node and reads it.
 *
 * <p>Structurally this is {@code DiscoveryEngine} on a thirty-second cadence, and the parallels are
 * load-bearing rather than cosmetic — which is why the shared part is shared code: {@link PluginCalls}
 * owns the fan-out and the timeout, and a plugin that throws is recorded {@code FAILED} rather than
 * propagating. ADR-0026 made the value explicit precisely so a failed observation is never mistaken
 * for a healthy one, and ADR-0046 makes {@code FAILED} leave the store untouched, so the last good
 * reading stands and goes visibly stale instead of the node flipping grey because one poll blinked.
 */
@Component
public class HealthEngine {

    private static final Logger log = LoggerFactory.getLogger(HealthEngine.class);

    private final BoundConfiguration configuration;
    private final GraphStore graph;
    private final HealthStore contributions;
    private final StateFoldRunner folds;
    private final Clock clock;

    public HealthEngine(
            BoundConfiguration configuration,
            GraphStore graph,
            HealthStore contributions,
            StateFoldRunner folds,
            Clock clock) {
        this.configuration = configuration;
        this.graph = graph;
        this.contributions = contributions;
        this.folds = folds;
        this.clock = clock;
    }

    /** One full pass over every configured pair. Also the seam the tests drive. */
    public void observeEveryEnvironment() {
        configuration.environments().forEach(environment -> observe(environment.key()));
    }

    public void observe(String environmentKey) {
        List<ConfiguredCapability> pairs = configuration.healthPairs(environmentKey);
        if (pairs.isEmpty()) {
            return;
        }

        List<ObservableNode> observable = graph.observable(environmentKey);
        // Strictly below the interval, so a hung plugin cannot overlap its own next run (ADR-0103).
        Duration timeout = configuration.refresh().health().dividedBy(2);

        PluginCalls.inParallel(pairs.stream()
                .map(pair -> (Runnable) () -> recordOnePair(pair, observable, timeout))
                .toList());

        folds.run(environmentKey);
    }

    private void recordOnePair(ConfiguredCapability pair, List<ObservableNode> observable, Duration timeout) {
        String pluginId = pair.plugin().id();
        List<ObservableNode> routed = observable.stream()
                .filter(node -> node.backings().stream()
                        .anyMatch(backing -> backing.plugin().equals(pluginId)))
                .toList();

        HealthResult result = invoke(pair, routed, timeout);
        contributions.record(pair.environmentKey(), pluginId, result, clock.instant());
        log.debug(
                "health {}/{} -> {} ({} of {} nodes routed, {} observed)",
                pair.environmentKey(),
                pluginId,
                result.outcome().status(),
                routed.size(),
                observable.size(),
                result.contributions().size());
    }

    @SuppressWarnings("unchecked")
    private HealthResult invoke(ConfiguredCapability pair, List<ObservableNode> routed, Duration timeout) {
        HealthCapability<Object> capability = (HealthCapability<Object>) pair.plugin();
        return PluginCalls.within(
                timeout,
                "health observation",
                () -> capability.observe(new HealthRequest<>(pair.environmentKey(), routed, pair.config())),
                cause -> failed(pair, cause));
    }

    private HealthResult failed(ConfiguredCapability pair, String cause) {
        log.warn("health {}/{} failed: {}", pair.environmentKey(), pair.plugin().id(), cause);
        return new HealthResult(Map.of(), Outcome.failed(cause));
    }
}
