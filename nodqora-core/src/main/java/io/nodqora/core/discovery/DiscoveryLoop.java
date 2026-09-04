package io.nodqora.core.discovery;

import io.nodqora.core.config.BoundConfiguration;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ADR-0012: two cadence loops, not per-plugin schedulers. ADR-0003's boundary is a cadence boundary,
 * so it becomes a slow topology loop calling Discovery and a fast state loop calling Health.
 *
 * <p>Only the slow loop exists in this slice: {@code yaml} declares no Health capability, so there is
 * nothing for a fast loop to call.
 *
 * <p>The interval is file-declared (ADR-0014) so it can change without a rebuild, and it is deliberate
 * that there is no refresh endpoint to shortcut it — ADR-0035 accepted five-minute discovery latency
 * and said latency complaints should reopen ADR-0012 rather than the cadence. The YAML iteration loop
 * pays for that, and the answer is a shorter interval in dev config, which is config and not code.
 */
@Component
public class DiscoveryLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryLoop.class);

    private final DiscoveryEngine engine;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;

    public DiscoveryLoop(DiscoveryEngine engine, BoundConfiguration configuration) {
        this.engine = engine;
        this.interval = configuration.refresh().discovery();
        ThreadFactory threads = Thread.ofVirtual().name("nodqora-discovery").factory();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(threads);
    }

    /**
     * Started explicitly by the startup sequence rather than on context refresh, so the config mirrors
     * are reconciled and the boot fold has run before the first poll can land a snapshot.
     */
    public void start() {
        log.info("discovery loop running every {}", interval);
        scheduler.scheduleWithFixedDelay(this::pollQuietly, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void pollQuietly() {
        try {
            engine.pollEveryEnvironment();
        } catch (RuntimeException e) {
            // A throw here would silently cancel the schedule for the life of the process.
            log.error("a discovery pass failed", e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
