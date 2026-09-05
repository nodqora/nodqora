// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.connect;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.Health;
import io.nodqora.plugin.api.HealthCapability.HealthResult;
import io.nodqora.plugin.api.HealthCapability.ObservableNode;
import io.nodqora.plugin.api.Outcome;
import io.nodqora.plugin.api.StateContribution;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One environment's Connect observation: one {@code GET /connectors?expand=status}, normalized per
 * ADR-0025.
 *
 * <p><b>The top-level connector state lies, and the fixture is built around it.</b> Research #5
 * verified from {@code AbstractHerder.connectorStatus()} that Connect performs no aggregation over
 * tasks, so {@code payments-iceberg-sink} reports {@code RUNNING} with one of three tasks
 * {@code FAILED}. Reading {@code connector.state} alone would render it {@code HEALTHY} — a
 * correctness bug rather than a simplification, and the reason this class reads {@code tasks[]}.
 *
 * <p>Two things it deliberately does <em>not</em> do:
 *
 * <ul>
 *   <li><b>Store the trace.</b> ADR-0028 takes its first line only, bounded, into
 *       {@code rawSignal}. The full trace is unbounded, carries no failure timestamp anywhere in the
 *       API, and both connector nodes already link to the Connect UI and to Logs — rotating
 *       unbounded traces through a table rewritten every thirty seconds buys little and costs a lot.
 *   <li><b>Return {@code UNKNOWN} for a connector it read.</b> Under ADR-0024 an abstention is
 *       discarded, so an {@code UNKNOWN} for something actually observed deletes this plugin's own
 *       vote. A connector that has vanished between polls has no contribution at all (ADR-0104).
 * </ul>
 */
class ConnectHealth {

    private static final Logger log = LoggerFactory.getLogger(ConnectHealth.class);

    /** ADR-0028's allow-list for this plugin, enumerated by name and never "whatever we read". */
    private static final String TASKS_TOTAL = "tasksTotal";

    private static final String TASKS_RUNNING = "tasksRunning";

    /** ADR-0028: a short line, so the trace contributes its first line and no more. */
    private static final int TRACE_BUDGET = 500;

    private final ConnectApi api;

    ConnectHealth(ConnectApi api) {
        this.api = api;
    }

    HealthResult observe(String environmentKey, List<ObservableNode> nodes, ConnectConfig config) {
        if (nodes.isEmpty()) {
            // Nothing carries a `connect` backing, so there is nothing to look at and no reason to
            // spend a round trip finding that out. COMPLETE with no contributions is truthful and
            // clears anything stale.
            return new HealthResult(Map.of(), Outcome.complete());
        }

        List<ConnectorStatus> listed;
        try {
            listed = api.statuses(config);
        } catch (RuntimeException e) {
            // ADR-0042 reports HTTP truthfully, and ADR-0046 then leaves the store untouched — so
            // the last good readings stand and go visibly stale rather than every connector in the
            // environment flipping grey because one poll blinked.
            return new HealthResult(Map.of(), Outcome.failed("GET /connectors failed: " + message(e)));
        }

        List<String> reasons = new ArrayList<>();
        Map<String, ConnectorStatus> byName = new LinkedHashMap<>();
        for (ConnectorStatus status : listed) {
            if (!config.connectors().admits(status.name())) {
                continue;
            }
            if (status.error() != null) {
                reasons.add("connector %s could not be expanded: %s".formatted(status.name(), status.error()));
                continue;
            }
            byName.put(folded(status.name()), status);
        }

        Map<String, StateContribution> contributions = new LinkedHashMap<>();
        for (ObservableNode node : nodes) {
            contribution(node, byName).ifPresent(observed -> contributions.put(node.key(), observed));
        }

        log.debug(
                "health {}/{}: {} node(s) routed, {} observed",
                environmentKey,
                ConnectDiscovery.PLUGIN_ID,
                nodes.size(),
                contributions.size());

        return new HealthResult(contributions, reasons.isEmpty() ? Outcome.complete() : Outcome.partial(reasons));
    }

    /**
     * ADR-0024's collapse, applied inside the plugin. A connector node normally carries exactly one
     * {@code connect} backing, so the collapse is the identity — but it is written as the general
     * case because there is one algorithm in the system, not a plugin-shaped variant of it, and a
     * second rule here would be the per-plugin precedence table ADR-0024 rejected arrived at by
     * drift.
     */
    private static Optional<StateContribution> contribution(ObservableNode node, Map<String, ConnectorStatus> byName) {
        List<ConnectorStatus> observed = node.backings().stream()
                .filter(backing -> ConnectDiscovery.PLUGIN_ID.equals(backing.plugin()))
                .filter(backing -> ConnectDiscovery.CONNECTOR_KIND.equals(backing.kind()))
                .map(Backing::reference)
                // A connector that has vanished abstains: deletion is discovery's to read from its
                // own snapshot, never encoded as health.
                .map(reference -> byName.get(folded(reference)))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ConnectorStatus::name))
                .toList();

        Health collapsed = Health.collapse(observed.stream().map(ConnectHealth::health).toList());
        if (collapsed == Health.UNKNOWN) {
            return Optional.empty();
        }
        return Optional.of(new StateContribution(collapsed, rawSignal(observed), metrics(observed)));
    }

    /**
     * ADR-0025's two tables, in order. Connector state decides first:
     *
     * <table>
     *   <tr><th>connector state</th><th>health</th></tr>
     *   <tr><td>{@code FAILED}</td><td>{@code UNHEALTHY}</td></tr>
     *   <tr><td>{@code PAUSED}, {@code STOPPED}</td><td>{@code DISABLED}</td></tr>
     *   <tr><td>{@code UNASSIGNED}, {@code RESTARTING}</td><td>{@code DEGRADED}</td></tr>
     *   <tr><td>{@code RUNNING}</td><td>the task arithmetic below</td></tr>
     * </table>
     *
     * <p>Then, for a {@code RUNNING} connector, <b>the replica rule unchanged</b> — all running
     * {@code HEALTHY}, some {@code DEGRADED}, none {@code UNHEALTHY} — over tasks rather than
     * replicas. Zero tasks is {@code DEGRADED} rather than {@code UNHEALTHY} because it is usually a
     * momentarily unassigned connector, which matches {@code UNASSIGNED} above.
     *
     * <p>{@code PAUSED} is {@code DISABLED} rather than {@code DEGRADED}, and this is the plugin that
     * makes the incident scenario's collapse interesting: a paused sink is judgement suspended, so it
     * wins ADR-0024's step 2 outright over the two observers that still read the connector's
     * StatefulSet and its lag as fine.
     */
    private static Health health(ConnectorStatus status) {
        String state = status.state() == null ? "" : status.state().toUpperCase(Locale.ROOT);
        return switch (state) {
            case "FAILED" -> Health.UNHEALTHY;
            case "PAUSED", "STOPPED" -> Health.DISABLED;
            case "UNASSIGNED", "RESTARTING" -> Health.DEGRADED;
            case "RUNNING" -> overTasks(status);
            // An unrecognised state is still an observation, and ADR-0029 forbids answering an
            // observation with an abstention. DEGRADED is the honest reading: something is off and
            // nobody here knows how badly.
            default -> Health.DEGRADED;
        };
    }

    private static Health overTasks(ConnectorStatus status) {
        int total = status.tasks().size();
        if (total == 0) {
            return Health.DEGRADED;
        }
        long running = status.running();
        if (running >= total) {
            return Health.HEALTHY;
        }
        return running == 0 ? Health.UNHEALTHY : Health.DEGRADED;
    }

    /**
     * ADR-0028: {@code "RUNNING, 3/3 tasks RUNNING"} — a short line and a <em>gist</em>, never an
     * expected-output assertion. The core joins one of these per plugin, so the fixture's
     * {@code payments-es-sink} reads {@code "2 desired / 2 ready; lag 120; RUNNING, 3/3 tasks
     * RUNNING"} once all three of its observers have spoken.
     *
     * <p>A failing task appends its trace's <b>first line only</b>, bounded. There is no failure
     * timestamp anywhere in the Connect API, so the trace is all there is, and it is the difference
     * between "something is wrong" and "the Elasticsearch endpoint refused the connection".
     */
    private static String rawSignal(List<ConnectorStatus> observed) {
        if (observed.isEmpty()) {
            return null;
        }
        boolean several = observed.size() > 1;
        return observed.stream()
                .map(status -> several ? status.name() + " " + phrase(status) : phrase(status))
                .collect(Collectors.joining(", "));
    }

    private static String phrase(ConnectorStatus status) {
        String line = "%s, %d/%d tasks RUNNING".formatted(status.state(), status.running(), status.tasks().size());
        String trace = status.tasks().stream()
                .filter(task -> !task.isRunning())
                .map(TaskStatus::trace)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return trace == null ? line : line + ": " + firstLine(trace);
    }

    private static String firstLine(String trace) {
        String first = trace.lines().findFirst().orElse("").trim();
        return first.length() <= TRACE_BUDGET ? first : first.substring(0, TRACE_BUDGET) + "…";
    }

    /** ADR-0028's allow-list: {@code tasksTotal} and {@code tasksRunning}, enumerated by name. */
    private static Map<String, Object> metrics(List<ConnectorStatus> observed) {
        if (observed.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> metrics = new TreeMap<>();
        metrics.put(TASKS_TOTAL, observed.stream().mapToInt(status -> status.tasks().size()).sum());
        metrics.put(TASKS_RUNNING, (int) observed.stream().mapToLong(ConnectorStatus::running).sum());
        return metrics;
    }

    private static String folded(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
