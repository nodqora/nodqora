package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * §8's <b>incident</b> scenario: the enricher is 3 desired / 0 ready in {@code CrashLoopBackOff}.
 *
 * <p>The scenario exists to be looked at rather than merely asserted. Its shape is a single failing
 * workload mid-pipeline with healthy upstream and stalled downstream, and the claim is that the
 * canvas makes the blast direction obvious without a blast-radius feature. What this slice can prove
 * is the half that is data: the enricher goes UNHEALTHY while {@code payments-api} immediately
 * upstream of it stays HEALTHY — which is <b>ADR-0027 working, not failing</b>. Health is local. A
 * propagated value would paint the whole downstream red and have no raw signal behind any of it,
 * flattening the very shape this scenario exists to show.
 *
 * <p>The scenario is a statement about the pipeline rather than about one technology, so all three
 * recordings carry a piece of it: a crash-looping workload, the lag that piles up behind it, and the
 * two sinks somebody paused. Each overlay differs from its baseline by a handful of values, and the
 * whole application runs over them unchanged.
 */
@TestPropertySource(properties = "nodqora.test.scenario=incident")
class IncidentScenarioTest extends NodqoraIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper mapper;

    private JsonNode state() {
        return Golden.normalize(http.getForObject("/api/environments/production/state", JsonNode.class));
    }

    @Test
    void the_incident_state_document_matches_its_golden() {
        assertThat(state()).isEqualTo(Golden.read(mapper, "state-production-incident.json"));
    }

    @Test
    void none_ready_is_unhealthy_and_its_neighbour_is_untouched() {
        JsonNode state = state();

        // ADR-0025: none ready → UNHEALTHY, the bottom of the all/some/none rule the baseline
        // exercises the middle of.
        assertThat(ReferencePipelineStateTest.node(state, "payments-enricher").get("health").asText())
                .isEqualTo("UNHEALTHY");
        assertThat(ReferencePipelineStateTest.node(state, "payments-enricher")
                        .get("rawSignal")
                        .asText())
                .isEqualTo("3 desired / 0 ready; lag 2100000");

        // ADR-0027: health is local, and never rolls up or is inferred from a neighbour. The two are
        // adjacent in the pipeline and their health is unrelated, which is the property that lets an
        // operator read the incident's origin off the canvas rather than its blast radius.
        assertThat(ReferencePipelineStateTest.node(state, "payments-api").get("health").asText())
                .isEqualTo("HEALTHY");
    }

    @Test
    void the_topics_either_side_read_their_own_lag_rather_than_inheriting_the_failure() {
        JsonNode state = state();

        // §8 gives both topics a lag-derived value under the incident, and neither is UNHEALTHY.
        // `raw.v1` is DEGRADED at 2.1M because the enricher stopped consuming it — a value it got
        // from its own consumer group, not from the node next to it.
        assertThat(ReferencePipelineStateTest.node(state, "payments.events.raw.v1")
                        .get("health")
                        .asText())
                .isEqualTo("DEGRADED");
        assertThat(ReferencePipelineStateTest.node(state, "payments.events.raw.v1")
                        .get("rawSignal")
                        .asText())
                .isEqualTo("lag 2100000");

        // ADR-0025's accepted cost, felt rather than fixed (ADR-0101). `enriched.v1` reads HEALTHY
        // straight through the incident: nothing produces to it *and* both sinks are paused, so its
        // groups' lag is frozen at the baseline's 8,400, under threshold. Its real signal — "no new
        // records for 20m" — is producer staleness, which needs two polls compared, and ADR-0012
        // makes plugins stateless. An idle topic reading HEALTHY is honest; the incident's shape is
        // still legible without it.
        assertThat(ReferencePipelineStateTest.node(state, "payments.events.enriched.v1")
                        .get("health")
                        .asText())
                .isEqualTo("HEALTHY");
    }

    @Test
    void the_paused_sinks_collapse_three_observers_to_disabled() {
        JsonNode state = state();

        // The case that defeated worst-wins, and the reason ADR-0024 is three steps rather than a
        // `max()`. `payments-es-sink` is observed by all three plugins at once and they disagree:
        // `connect` says DISABLED (the connector is PAUSED), `kafka` says HEALTHY at lag 120 behind
        // it, `kubernetes` says HEALTHY because the StatefulSet hosting it is 2/2 ready. On any
        // severity ladder DISABLED is not the maximum; the fixture says the node is DISABLED anyway,
        // because a human turned it off and everything behind that is a consequence rather than a
        // finding. Both sinks take this path, and `payments-iceberg-sink` takes it over a `kafka`
        // contribution of DEGRADED — DISABLED wins outright, including over a real severity.
        for (String sink : new String[] {"payments-es-sink", "payments-iceberg-sink"}) {
            assertThat(ReferencePipelineStateTest.node(state, sink).get("health").asText())
                    .as("%s collapses three observers", sink)
                    .isEqualTo("DISABLED");
            // ADR-0028: every observer still gets its say in the signal. The collapse discards
            // votes, never evidence.
            assertThat(ReferencePipelineStateTest.node(state, sink).get("rawSignal").asText())
                    .contains("ready", "lag ", "PAUSED");
        }
    }

    @Test
    void the_topology_is_unchanged_because_health_is_not_topology() {
        JsonNode graph = Golden.normalize(
                http.getForObject("/api/environments/production/graph", JsonNode.class));

        // ADR-0003's wall, asserted rather than assumed: the incident changes a readiness count and
        // nothing else, so the graph document is byte-identical to the baseline's golden. If health
        // ever leaked onto a Node — or if `updatedAt` moved because a fold saw a changed object —
        // this is where it would show, and ADR-0050 says that failure has no other symptom.
        assertThat(graph).isEqualTo(Golden.read(mapper, "graph-production.json"));
    }

    @Test
    void staging_reads_the_baseline_because_the_overlay_carries_only_production() {
        // The scenario is an overlay, not a second recording: `incident/` holds one file. Staging
        // falling through to the baseline is what keeps the fixture from growing a second copy of
        // itself, which is the drift ADR-0099 spent its whole design avoiding.
        assertThatNoException()
                .isThrownBy(() -> http.getForObject("/api/environments/staging/state", JsonNode.class));
        JsonNode staging = Golden.normalize(
                http.getForObject("/api/environments/staging/state", JsonNode.class));
        assertThat(ReferencePipelineStateTest.node(staging, "payments-enricher")
                        .get("health")
                        .asText())
                .isEqualTo("HEALTHY");
    }
}
