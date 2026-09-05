package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * ADR-0026's worked example, made real: <b>the Connect cluster goes dark, and the graph says so.</b>
 *
 * <p>This is the case the whole honesty layer exists for. With {@code connect} unreachable, ADR-0024
 * discards the abstention, so {@code payments-es-sink} composes down to {@code kubernetes} at 2/2 and
 * {@code kafka} at lag 120 and renders <b>{@code HEALTHY}</b> while nobody knows what its tasks are
 * doing. <em>Green because we stopped looking is the oldest failure mode in monitoring.</em>
 *
 * <p>The tempting fix — return {@code UNKNOWN} on failure and let it beat everything — was rejected
 * twice over: it re-overloads {@code UNKNOWN}, which ADR-0024 had just made mean <em>abstention</em>,
 * and it lets one Connect blip erase a genuine Kubernetes signal. So {@code health} is left alone and
 * the failure surfaces on the other channel entirely.
 *
 * <p><b>The cluster is cut after a healthy poll rather than being dark from the start</b>, because
 * ADR-0043 makes the store durable — <em>"on boot it already holds the last accepted snapshot per
 * pair"</em>. A cluster that has never answered leaves the connectors with no backings at all, so
 * the health loop routes nothing to {@code connect} and its poll completes vacuously; there is no
 * blind node because there is no node. The interesting state, and the only one an operator meets, is
 * a cluster that <em>was</em> answering.
 *
 * <p>The scenario is <b>two axes at once</b>, which is the point ADR-0083 built its {@code blind}
 * fixture to make. The enricher is honestly {@code DISABLED} because somebody scaled it to zero
 * (ADR-0034), while both connectors are dishonestly calm — and <b>under ADR-0017 alone all three
 * render quiet</b>. Everything that separates them lives on the outcome channel.
 */
@TestPropertySource(properties = "nodqora.test.scenario=scaled-to-zero")
class BlindObserverTest extends NodqoraIntegrationTest {

    @Autowired
    TestRestTemplate http;

    /** Runs after the base class's healthy poll, so the store already holds a complete snapshot. */
    @BeforeEach
    void theClusterGoesDark() {
        reachability.cut("connect");
        discovery.pollEveryEnvironment();
        health.observeEveryEnvironment();
    }

    private JsonNode state() {
        return http.getForObject("/api/environments/production/state", JsonNode.class);
    }

    private JsonNode graph() {
        return http.getForObject("/api/environments/production/graph", JsonNode.class);
    }

    private static JsonNode pair(JsonNode document, String plugin) {
        for (JsonNode entry : document.get("plugins")) {
            if (entry.get("plugin").asText().equals(plugin)) {
                return entry;
            }
        }
        throw new AssertionError("no plugins[] entry for " + plugin);
    }

    @Test
    void the_connectors_read_healthy_on_a_reading_that_is_no_longer_current() {
        JsonNode state = state();

        // Not a bug, and not something to repair on the health channel. ADR-0026 accepted this
        // residual gap explicitly, "on the understanding that the plugin status is shown" — which is
        // the next test, and which ADR-0081 renders.
        // ADR-0026's own example, unchanged by the outage: HEALTHY, with `connect` the only observer
        // that could have said otherwise.
        assertThat(ReferencePipelineStateTest.node(state, "payments-es-sink")
                        .get("health")
                        .asText())
                .isEqualTo("HEALTHY");

        // The sharper half. `payments-iceberg-sink` reads DEGRADED, and that DEGRADED is *entirely*
        // `connect`'s — one of three tasks FAILED, which no other plugin can see. So the outage
        // leaves a node reporting a finding nobody has re-checked, in both directions at once.
        assertThat(ReferencePipelineStateTest.node(state, "payments-iceberg-sink")
                        .get("health")
                        .asText())
                .isEqualTo("DEGRADED");

        // ADR-0106, and the reason it had to be written. ADR-0083 predicted the node would "compose
        // down to kubernetes + kafka", but ADR-0072 retains contributions per plugin by ADR-0046's
        // rules, so a FAILED run is a no-op on the contribution store and `connect`'s pre-outage
        // segment is *still in the signal*. The real failure is worse than the one ADR-0026
        // described: the value is not merely under-observed, it is partly out of date.
        for (String sink : List.of("payments-es-sink", "payments-iceberg-sink")) {
            assertThat(ReferencePipelineStateTest.node(state, sink).get("rawSignal").asText())
                    .as("%s still carries the reading connect took before the outage", sink)
                    .contains("ready", "lag ", "RUNNING");
        }
    }

    @Test
    void the_freshness_of_a_blind_node_goes_stale_while_its_neighbours_stay_current() {
        JsonNode state = state();

        // ADR-0072 made a composed `observedAt` the **min** over contributions precisely so that "a
        // plugin which could not look must not let the others make a node look fine". Here that pays
        // without anything being added for it: the retained `connect` contribution drags the sinks'
        // freshness back to before the outage, while the enricher — observed only by plugins that
        // still answer — is current. A `max` would have presented both as equally fresh.
        Instant sink = Instant.parse(ReferencePipelineStateTest.node(state, "payments-es-sink")
                .get("observedAt")
                .asText());
        Instant enricher = Instant.parse(ReferencePipelineStateTest.node(state, "payments-enricher")
                .get("observedAt")
                .asText());

        assertThat(sink).isBefore(enricher);
    }

    @Test
    void the_health_roster_says_connect_failed_and_says_why() {
        JsonNode connect = pair(state(), "connect");

        // ADR-0026 mirrored ADR-0012 on the health side for exactly this. Without this block, the
        // previous test's HEALTHY is a lie with nothing anywhere in the system to contradict it.
        assertThat(connect.get("capability").asText()).isEqualTo("HEALTH");
        assertThat(connect.get("outcome").asText()).isEqualTo("FAILED");
        assertThat(connect.get("reasons").get(0).asText()).contains("GET /connectors");
        assertThat(connect.get("recordedAt").isNull()).isFalse();

        // Scoped to the plugin that had the problem, rather than contaminating every node's health
        // with the worst infrastructure problem anywhere in the environment.
        assertThat(pair(state(), "kubernetes").get("outcome").asText()).isEqualTo("COMPLETE");
        assertThat(pair(state(), "kafka").get("outcome").asText()).isEqualTo("COMPLETE");
    }

    @Test
    void the_disabled_enricher_is_distinguishable_from_the_calm_connectors() {
        // ADR-0083's question four. The enricher is DISABLED on evidence of intent — `spec.replicas:
        // 0` is a recorded decision (ADR-0029, ADR-0034) — and nothing about it is under-observed.
        // The connectors read HEALTHY and are not.
        assertThat(ReferencePipelineStateTest.node(state(), "payments-enricher")
                        .get("health")
                        .asText())
                .isEqualTo("DISABLED");

        // What separates them is which plugins *back* them, which is why ADR-0013's backings and not
        // `sources[]` are what the blind mark reads: a plugin that merely discovered a node has no
        // observation to abstain from.
        assertThat(backingPlugins(graph(), "payments-enricher")).doesNotContain("connect");
        assertThat(backingPlugins(graph(), "payments-es-sink")).contains("connect");
    }

    @Test
    void a_failed_poll_leaves_the_topology_completely_alone() {
        JsonNode graph = graph();

        // ADR-0046's third row, end to end: `FAILED` touches nothing, so the two connectors, their
        // backings and the two SOURCES_FROM edges all survive intact. ADR-0012 made the value
        // explicit so that a FAILED snapshot is never mistaken for an empty one, and this is a
        // rehearsal of exactly the mistake — the connectors would otherwise vanish from the canvas
        // because a cluster stopped answering.
        assertThat(graph.get("nodes")).hasSize(10);
        assertThat(graph.get("edges")).hasSize(9);
        assertThat(ReferencePipelineGraphTest.node(graph, "payments-es-sink").get("backings"))
                .isNotEmpty();
        assertThat(pair(graph, "connect").get("outcome").asText()).isEqualTo("FAILED");
    }

    @Test
    void every_key_connect_carried_is_now_retained_because_only_the_header_moved() {
        JsonNode graph = graph();
        Instant recordedAt = Instant.parse(pair(graph, "connect").get("recordedAt").asText());

        // ADR-0084's comparison, on the wire. `retained(source) := confirmedAt < recordedAt`, with no
        // threshold, no TTL and no constant anywhere. It works here only because ADR-0086 writes the
        // header on a failed poll: under ADR-0071's literal reading — "FAILED does nothing" — both
        // timestamps would sit still, no key would be older than anything, and retention would
        // silently stop working during exactly the outage it exists for.
        for (String sink : List.of("payments-es-sink", "payments-iceberg-sink")) {
            assertThat(confirmedAt(graph, sink, "connect"))
                    .as("%s was carried forward by connect rather than confirmed", sink)
                    .isBefore(recordedAt);
        }

        // Per key, not per plugin, and per plugin, not per environment. `yaml` polled cleanly in the
        // same pass, so its own keys are confirmed rather than retained — which is the distinction
        // ADR-0056 said an environment-level banner could never make.
        Instant yamlRecordedAt = Instant.parse(pair(graph, "yaml").get("recordedAt").asText());
        assertThat(confirmedAt(graph, "payments-es-sink", "yaml")).isAfterOrEqualTo(yamlRecordedAt);
    }

    private static Instant confirmedAt(JsonNode graph, String key, String plugin) {
        for (JsonNode source : ReferencePipelineGraphTest.node(graph, key).get("sources")) {
            if (source.get("plugin").asText().equals(plugin)) {
                return Instant.parse(source.get("confirmedAt").asText());
            }
        }
        throw new AssertionError("%s has no %s source".formatted(key, plugin));
    }

    private static List<String> backingPlugins(JsonNode graph, String key) {
        List<String> plugins = new ArrayList<>();
        ReferencePipelineGraphTest.node(graph, key)
                .get("backings")
                .forEach(backing -> plugins.add(backing.get("plugin").asText()));
        return plugins;
    }
}
