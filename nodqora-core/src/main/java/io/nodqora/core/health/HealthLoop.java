package io.nodqora.core.health;

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
 * ADR-0012's second cadence loop, and the one that makes ADR-0003's wall visible at runtime: a slow
 * topology loop calling Discovery every five minutes, and this one calling Health every thirty
 * seconds.
 *
 * <p>They are two schedulers rather than two branches in one because the split is the whole point —
 * a single combined loop would either refetch topology 120x more often than it changes or blank a
 * node's health for five minutes at a time. ADR-0035 chose thirty seconds because an SRE watching a
 * rollout wants readiness inside a minute; the interval is file-declared (ADR-0014, ADR-0103) so
 * that number can be tuned without a rebuild, and there is deliberately no refresh endpoint to
 * shortcut it.
 */
@Component
public class HealthLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HealthLoop.class);

    private final HealthEngine engine;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;

    public HealthLoop(HealthEngine engine, BoundConfiguration configuration) {
        this.engine = engine;
        this.interval = configuration.refresh().health();
        ThreadFactory threads = Thread.ofVirtual().name("nodqora-health").factory();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(threads);
    }

    /**
     * Started by the startup sequence after the boot folds, so the first observation is routed by a
     * graph that exists. A health pass over an empty node table would route nothing to anybody and
     * record a {@code COMPLETE} run saying so — true, useless, and immediately overwritten.
     */
    public void start() {
        log.info("health loop running every {}", interval);
        scheduler.scheduleWithFixedDelay(this::observeQuietly, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void observeQuietly() {
        try {
            engine.observeEveryEnvironment();
        } catch (RuntimeException e) {
            // A throw here would silently cancel the schedule for the life of the process.
            log.error("a health pass failed", e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
