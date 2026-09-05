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
 * <p>The recording differs from the baseline by exactly one number, and the whole application runs
 * over it unchanged.
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
                .isEqualTo("3 desired / 0 ready");

        // ADR-0027: health is local, and never rolls up or is inferred from a neighbour. The two are
        // adjacent in the pipeline and their health is unrelated, which is the property that lets an
        // operator read the incident's origin off the canvas rather than its blast radius.
        assertThat(ReferencePipelineStateTest.node(state, "payments-api").get("health").asText())
                .isEqualTo("HEALTHY");
    }

    @Test
    void the_topics_either_side_stay_unknown_rather_than_inheriting_the_failure() {
        JsonNode state = state();

        // §8 gives both topics a lag-derived value under the incident, and neither is UNHEALTHY:
        // raw.v1 DEGRADED because it is backing up, enriched.v1 HEALTHY because an idle topic is
        // not a broken one. Both wait for `kafka`. What matters here is that they do not acquire a
        // value from the enricher between them — the absence is honest, and a propagated UNHEALTHY
        // would be a value with no raw signal behind it.
        assertThat(ReferencePipelineStateTest.node(state, "payments.events.raw.v1")
                        .get("health")
                        .asText())
                .isEqualTo("UNKNOWN");
        assertThat(ReferencePipelineStateTest.node(state, "payments.events.enriched.v1")
                        .get("health")
                        .asText())
                .isEqualTo("UNKNOWN");
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
