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
import io.nodqora.plugin.api.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * load-bearing rather than cosmetic: pairs run in parallel on virtual threads under an engine-imposed
 * timeout, one failing pair does not touch the others, and a plugin that throws is recorded
 * {@code FAILED} rather than propagating — because ADR-0026 made the value explicit precisely so a
 * failed observation is never mistaken for a healthy one, and ADR-0046 makes {@code FAILED} leave the
 * store untouched so the last good reading stands and goes visibly stale instead.
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

        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = new ArrayList<>();
            pairs.forEach(pair -> running.add(workers.submit(() -> recordOnePair(pair, observable, timeout))));
            running.forEach(HealthEngine::awaitQuietly);
        }

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
        try (ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HealthResult> call = worker.submit(() ->
                    capability.observe(new HealthRequest<>(pair.environmentKey(), routed, pair.config())));
            try {
                return call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                call.cancel(true);
                return failed(pair, "health observation timed out after " + timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failed(pair, "health observation was interrupted");
            } catch (Exception e) {
                return failed(
                        pair, "health observation threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private HealthResult failed(ConfiguredCapability pair, String cause) {
        log.warn("health {}/{} failed: {}", pair.environmentKey(), pair.plugin().id(), cause);
        return new HealthResult(Map.of(), Outcome.failed(cause));
    }

    private static void awaitQuietly(Future<?> task) {
        try {
            task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("a health pair failed outside the plugin call", e);
        }
    }
}
