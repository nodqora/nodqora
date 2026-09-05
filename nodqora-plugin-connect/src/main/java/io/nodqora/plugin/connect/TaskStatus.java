// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.connect;

/**
 * One task of one connector. {@code state} is Connect's own vocabulary — {@code RUNNING},
 * {@code FAILED}, {@code PAUSED}, {@code UNASSIGNED}, {@code RESTARTING}.
 *
 * <p><b>This record is why the health rule reads {@code tasks[]} at all.</b> Research #5 verified
 * from {@code AbstractHerder.connectorStatus()} that the top-level connector state performs no
 * aggregation over its tasks, so the fixture's {@code payments-iceberg-sink} reports {@code RUNNING}
 * while one of its three tasks is {@code FAILED}. Reading {@code connector.state} alone is a
 * correctness bug, not a simplification (ADR-0025).
 *
 * <p>{@code trace} is an unbounded stack trace with no failure timestamp anywhere in the API.
 * ADR-0028 takes its <em>first line only</em>, bounded, into {@code rawSignal} and stores none of it.
 */
public record TaskStatus(int id, String state, String trace) {

    public TaskStatus(int id, String state) {
        this(id, state, null);
    }

    /**
     * ADR-0025: every task state collapses to a boolean, with {@code FAILED}, {@code RESTARTING} and
     * {@code UNASSIGNED} alike counting as not-running. The replica rule then applies unchanged, so
     * there is one arithmetic in the system rather than two that must be kept in step.
     */
    public boolean isRunning() {
        return "RUNNING".equalsIgnoreCase(state);
    }
}
