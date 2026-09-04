package io.nodqora.plugin.api;

/**
 * ADR-0012. Every value names an effect on the snapshot store (ADR-0046), which is why
 * ADR-0085 declined to add a fourth: a pair that has never reported has no outcome at all.
 */
public enum OutcomeStatus {
    COMPLETE,
    PARTIAL,
    FAILED,
}
