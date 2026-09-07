// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The enricher scaled to zero, end to end — the one path in this slice that produces
 * {@code DISABLED}, and the reason {@code kubernetes} is the plugin allowed to produce it.
 *
 * <p>ADR-0029 requires <b>evidence of intent</b> before anything may be called {@code DISABLED}, and
 * absence of activity is never sufficient. Kubernetes is the case it left open: intent there is
 * <em>declarative</em> and sits in the object's own spec, so {@code spec.replicas: 0} is a recorded
 * decision rather than an inference from something not happening. The mirror-image case is what
 * makes the rule bite — an {@code EMPTY} consumer group is the exact state of a scaled-down consumer
 * and a crashed one, which is why the plugin that will read that group may never emit
 * {@code DISABLED} about it, even though it is watching the same node.
 *
 * <p>Nothing about the topology moves: the Deployment still exists, still backs the node, and the
 * graph document is unchanged. Only the fast half has anything to say.
 */
@TestPropertySource(properties = "nodqora.test.scenario=scaled-to-zero")
class ScaledToZeroTest extends NodqoraIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void a_workload_scaled_to_zero_renders_disabled_rather_than_unhealthy() {
        JsonNode state = http.getForObject("/api/environments/production/state", JsonNode.class);
        JsonNode enricher = ReferencePipelineStateTest.node(state, "payments-enricher");

        // Not UNHEALTHY. Nought ready of nought desired is not a failure, and paging an on-call at
        // 3am for a deliberate scale-down is the outcome ADR-0034 exists to prevent.
        assertThat(enricher.get("health").asText()).isEqualTo("DISABLED");
        // And not DEGRADED either, which is the sharper half now that `kafka` watches this node too.
        // Turning the consumer off does not stop its lag growing, so `kafka` contributes DEGRADED at
        // 40,000 in the same breath — a real severity, over a real reading, that ADR-0024 discards
        // anyway. This is step 2 of the collapse in its plainest form: DISABLED wins outright
        // because the lag *is* the scale-down, observed from the other side.
        assertThat(enricher.get("rawSignal").asText()).isEqualTo("scaled to 0; lag 40000");
    }

    @Test
    void the_metrics_still_report_the_zero_because_zero_is_a_reading() {
        JsonNode metrics = ReferencePipelineStateTest.node(
                        http.getForObject("/api/environments/production/state", JsonNode.class), "payments-enricher")
                .get("metrics")
                .get("kubernetes");

        // The distinction the nullable fields exist for: `desiredReplicas: 0` is a value somebody
        // declared, not a value we failed to read. A workload whose spec we could not read abstains
        // and has no metrics at all.
        assertThat(metrics.get("desiredReplicas").asInt()).isZero();
        assertThat(metrics.get("readyReplicas").asInt()).isZero();
    }

    @Test
    void its_neighbour_is_untouched_and_the_topology_has_not_moved() {
        JsonNode state = http.getForObject("/api/environments/production/state", JsonNode.class);
        JsonNode graph = http.getForObject("/api/environments/production/graph", JsonNode.class);

        // ADR-0027: health is local, so turning one node off says nothing about the next one.
        assertThat(ReferencePipelineStateTest.node(state, "payments-api").get("health").asText())
                .isEqualTo("HEALTHY");
        // ADR-0003: a replica count is runtime state. The Deployment still exists and still backs
        // the node, so the slow half cannot tell this happened — which is the wall doing its job.
        assertThat(graph.get("nodes")).hasSize(10);
        assertThat(ReferencePipelineGraphTest.node(graph, "payments-enricher").get("backings"))
                .isNotEmpty();
    }
}
