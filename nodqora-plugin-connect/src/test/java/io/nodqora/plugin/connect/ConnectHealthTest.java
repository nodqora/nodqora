package io.nodqora.plugin.connect;

import static org.assertj.core.api.Assertions.assertThat;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.Health;
import io.nodqora.plugin.api.HealthCapability.HealthRequest;
import io.nodqora.plugin.api.HealthCapability.HealthResult;
import io.nodqora.plugin.api.HealthCapability.ObservableNode;
import io.nodqora.plugin.api.OutcomeStatus;
import io.nodqora.plugin.api.StateContribution;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The {@code connect} plugin's health half — ADR-0025's two tables, over the recorded cluster. */
class ConnectHealthTest {

    private static final ConnectConfig CONFIG = new ConnectConfig(
            "https://connect-prod.internal:8083",
            null,
            new ConnectConfig.Connectors(List.of("payments-"), List.of("payments-debug-reprocessor")),
            new ConnectConfig.Workload("kubernetes", "statefulset", "payments-prod/kafka-connect"),
            null);

    @Test
    void a_running_connector_with_a_failed_task_is_degraded() {
        StateContribution iceberg = observe(new RecordedConnectApi()).get("payments-iceberg-sink");

        // §7's whole reason for existing, and the product plan's own §13 DEGRADED example. Research
        // #5 verified from `AbstractHerder.connectorStatus()` that the top-level state performs no
        // aggregation over tasks, so this connector reports RUNNING while one of three tasks is
        // FAILED. Reading `connector.state` alone renders it HEALTHY — a correctness bug.
        assertThat(iceberg.health()).isEqualTo(Health.DEGRADED);
        assertThat(iceberg.metrics()).containsOnly(Map.entry("tasksTotal", 3), Map.entry("tasksRunning", 2));
    }

    @Test
    void the_failing_tasks_trace_contributes_its_first_line_and_no_more() {
        String signal = observe(new RecordedConnectApi()).get("payments-iceberg-sink").rawSignal();

        // ADR-0028: a short line, with the trace's first line only. There is no failure timestamp
        // anywhere in the Connect API, so the trace is all there is — and it is the difference
        // between "something is wrong" and "the Iceberg commit timed out". The rest is not stored:
        // both connector nodes already link to the Connect UI and to Logs.
        assertThat(signal)
                .isEqualTo("RUNNING, 2/3 tasks RUNNING: org.apache.kafka.connect.errors.ConnectException: "
                        + "Commit to Iceberg catalog analytics timed out");
        assertThat(signal).doesNotContain("\n").doesNotContain("\tat ");
    }

    @Test
    void a_fully_running_connector_is_healthy_and_says_so_briefly() {
        StateContribution es = observe(new RecordedConnectApi()).get("payments-es-sink");

        assertThat(es.health()).isEqualTo(Health.HEALTHY);
        assertThat(es.rawSignal()).isEqualTo("RUNNING, 3/3 tasks RUNNING");
    }

    @Test
    void a_paused_connector_is_disabled_rather_than_degraded() {
        Map<String, StateContribution> incident = observe(RecordedConnectApi.scenario("incident"));

        // ADR-0025: PAUSED and STOPPED both mean DISABLED, and this is what makes the incident's
        // collapse the case ADR-0024 was written for. Both connectors are observed by three plugins
        // at once — `kubernetes` reads the StatefulSet hosting them as 2/2, `kafka` reads their lag
        // as frozen under threshold — and DISABLED wins outright rather than competing on severity.
        assertThat(incident.get("payments-es-sink").health()).isEqualTo(Health.DISABLED);
        assertThat(incident.get("payments-iceberg-sink").health()).isEqualTo(Health.DISABLED);
    }

    @ParameterizedTest
    @CsvSource({
        "FAILED,      3, 0, UNHEALTHY",
        "PAUSED,      3, 3, DISABLED",
        "STOPPED,     3, 3, DISABLED",
        "UNASSIGNED,  3, 3, DEGRADED",
        "RESTARTING,  3, 3, DEGRADED",
        "RUNNING,     3, 3, HEALTHY",
        "RUNNING,     3, 2, DEGRADED",
        "RUNNING,     3, 0, UNHEALTHY",
        "RUNNING,     0, 0, DEGRADED",
    })
    void connector_state_decides_first_and_then_the_replica_rule_runs_over_tasks(
            String state, int total, int running, Health expected) {
        List<TaskStatus> tasks = new java.util.ArrayList<>();
        for (int id = 0; id < total; id++) {
            tasks.add(new TaskStatus(id, id < running ? "RUNNING" : "FAILED"));
        }

        // ADR-0025's answer to research #5's three open shape questions, in one table: all tasks
        // failed is UNHEALTHY by symmetry with zero ready replicas; PAUSED and STOPPED both mean
        // DISABLED; and UNASSIGNED/RESTARTING are DEGRADED, never UNKNOWN. Zero tasks is DEGRADED
        // rather than UNHEALTHY because it is usually a momentarily unassigned connector.
        assertThat(observe(api(new ConnectorStatus("payments-es-sink", state, tasks)))
                        .get("payments-es-sink")
                        .health())
                .isEqualTo(expected);
    }

    @Test
    void a_connector_that_has_vanished_abstains_rather_than_reading_unhealthy() {
        // Discovery runs at five minutes and this at thirty seconds, so a connector somebody deleted
        // would otherwise paint ten cycles of red for something a human removed. There is nothing
        // there to observe, which is the "could not look" branch — and ADR-0104 makes an abstention
        // an omission rather than an UNKNOWN the fold would discard a step later anyway.
        assertThat(observe(api())).isEmpty();
    }

    @Test
    void nothing_routed_means_no_round_trip_and_a_complete_outcome() {
        HealthResult result = new ConnectPlugin(unreachable())
                .observe(new HealthRequest<>("production", List.of(), CONFIG));

        // Nothing carries a `connect` backing, so there is nothing this plugin could look at. The
        // API would have thrown had it been called, which is what makes this assertion about the
        // call rather than about the result.
        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.COMPLETE);
        assertThat(result.contributions()).isEmpty();
    }

    @Test
    void an_unreachable_cluster_is_failed_and_leaves_the_store_alone() {
        HealthResult result = new ConnectPlugin(unreachable())
                .observe(new HealthRequest<>("production", List.of(routed("payments-es-sink")), CONFIG));

        // ADR-0042 reports HTTP truthfully; ADR-0046 then leaves the store untouched, so the last
        // good readings stand and go visibly stale rather than every connector flipping grey because
        // one poll blinked. Slice 5 is what makes that staleness visible.
        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.FAILED);
        assertThat(result.contributions()).isEmpty();
    }

    @Test
    void a_per_connector_expansion_failure_is_partial_and_the_rest_still_land() {
        HealthResult result = new ConnectPlugin(api(
                        new ConnectorStatus("payments-es-sink", "RUNNING", List.of(new TaskStatus(0, "RUNNING"))),
                        new ConnectorStatus("payments-iceberg-sink", null, List.of(), "error_code 500")))
                .observe(new HealthRequest<>(
                        "production",
                        List.of(routed("payments-es-sink"), routed("payments-iceberg-sink")),
                        CONFIG));

        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.PARTIAL);
        assertThat(result.contributions()).containsOnlyKeys("payments-es-sink");
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, StateContribution> observe(ConnectApi api) {
        return new ConnectPlugin(api)
                .observe(new HealthRequest<>(
                        "production",
                        List.of(routed("payments-es-sink"), routed("payments-iceberg-sink")),
                        CONFIG))
                .contributions();
    }

    /** A folded node as the engine hands it over: the key, and the backings that routed it here. */
    private static ObservableNode routed(String connectorName) {
        return new ObservableNode(
                connectorName,
                List.of(
                        new Backing("connect", "connector", connectorName),
                        new Backing("kubernetes", "statefulset", "payments-prod/kafka-connect")));
    }

    private static ConnectApi api(ConnectorStatus... statuses) {
        return new ConnectApi() {
            @Override
            public List<ConnectorInfo> connectors(ConnectConfig config) {
                return List.of();
            }

            @Override
            public List<ConnectorStatus> statuses(ConnectConfig config) {
                return List.of(statuses);
            }
        };
    }

    private static ConnectApi unreachable() {
        return new ConnectApi() {
            @Override
            public List<ConnectorInfo> connectors(ConnectConfig config) {
                throw new ConnectApiException("connection refused");
            }

            @Override
            public List<ConnectorStatus> statuses(ConnectConfig config) {
                throw new ConnectApiException("connection refused");
            }
        };
    }
}
