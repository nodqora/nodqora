// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.connect;

import java.util.List;
import java.util.Objects;

/**
 * One entry of {@code GET /connectors?expand=status} — how a connector is <em>doing</em>, on the
 * fast loop. One HTTP request serves the whole cluster, resolved in-process on the receiving worker,
 * which is what makes ADR-0042's thirty seconds affordable.
 *
 * <p>{@code state} is the connector's own state and is <b>not</b> an aggregate over {@link #tasks()}
 * — see {@link TaskStatus}.
 */
public record ConnectorStatus(String name, String state, List<TaskStatus> tasks, String error) {

    public ConnectorStatus {
        Objects.requireNonNull(name, "name");
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    public ConnectorStatus(String name, String state, List<TaskStatus> tasks) {
        this(name, state, tasks, null);
    }

    public long running() {
        return tasks.stream().filter(TaskStatus::isRunning).count();
    }
}
