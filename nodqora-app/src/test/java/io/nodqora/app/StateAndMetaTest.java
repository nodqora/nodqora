package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * {@code /api/meta}, and the corners of {@code /state} that are about the endpoint rather than about
 * the reference pipeline — which {@link ReferencePipelineStateTest} owns.
 */
class StateAndMetaTest extends NodqoraIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void a_node_no_plugin_can_observe_is_unknown_and_that_is_the_honest_answer() {
        JsonNode state = http.getForObject("/api/environments/production/state", JsonNode.class);

        // ADR-0011: `yaml` declares Discovery and not Health, and never will, so its declared-only
        // nodes are permanently UNKNOWN — a consequence of the capability set rather than a rule
        // anyone wrote. ADR-0028 makes the rendering arithmetic: no contributions, no row, and the
        // outer join synthesizes all four fields.
        //
        // The prediction slice 1 made here held: when contributions arrived, the frontend's health
        // path did not change. Only its source did.
        JsonNode declared = ReferencePipelineStateTest.node(state, "stripe-webhooks");
        assertThat(declared.get("health").asText()).isEqualTo("UNKNOWN");
        assertThat(declared.get("rawSignal").isNull()).isTrue();
        assertThat(declared.get("metrics")).isEmpty();
        assertThat(declared.get("observedAt").isNull()).isTrue();
    }

    @Test
    void meta_publishes_the_rosters_and_the_server_s_own_intervals() {
        JsonNode meta = http.getForObject("/api/meta", JsonNode.class);

        assertThat(meta.get("environments").findValuesAsText("key")).containsExactly("production", "staging");
        assertThat(meta.get("plugins").findValuesAsText("id"))
                .containsExactly("yaml", "kubernetes", "kafka", "connect");
        // ADR-0010: present iff the bean implements the interface — that is the whole of capability
        // negotiation, with nothing declared anywhere to keep in step. `yaml` produces topology and
        // never observes it (ADR-0029); the other three do both, and that second capability is the
        // reason anything on the canvas has a colour. The full roster is one plugin that only
        // declares and three that also observe, which is exactly what the MVP set out to be.
        assertThat(capabilities(meta, 0)).containsExactly("DISCOVERY");
        assertThat(capabilities(meta, 1)).containsExactly("DISCOVERY", "HEALTH");
        assertThat(capabilities(meta, 2)).containsExactly("DISCOVERY", "HEALTH");
        assertThat(capabilities(meta, 3)).containsExactly("DISCOVERY", "HEALTH");
        // ADR-0059: the frontend does not hold a copy of file config that disagrees, silently, the
        // first time anyone tunes a plugin's cadence.
        assertThat(meta.get("refresh").get("graphSeconds").asLong()).isEqualTo(300);
        assertThat(meta.get("refresh").get("stateSeconds").asLong()).isEqualTo(30);
    }

    @Test
    void meta_never_publishes_connection_configuration() {
        String meta = http.getForObject("/api/meta", String.class);

        // ADR-0014, §40: key and display name only.
        assertThat(meta).doesNotContain("fixtures/reference-pipeline").doesNotContain("dir");
    }

    private static List<String> capabilities(JsonNode meta, int plugin) {
        List<String> capabilities = new java.util.ArrayList<>();
        meta.get("plugins").get(plugin).get("capabilities").forEach(value -> capabilities.add(value.asText()));
        return capabilities;
    }
}
