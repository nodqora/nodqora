package io.nodqora.plugin.api;

/**
 * The normalized operational state of a Node — the one closed vocabulary in a model of open
 * strings (ADR-0003, ADR-0024).
 *
 * <p>The five values are not a ladder. {@code UNKNOWN} is an abstention and {@code DISABLED} is
 * judgement suspended; only {@code HEALTHY < DEGRADED < UNHEALTHY} is a severity order.
 */
public enum Health {
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
    UNKNOWN,
    DISABLED,
}
