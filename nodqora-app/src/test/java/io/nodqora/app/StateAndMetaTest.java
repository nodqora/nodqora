package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/** The other two GETs. */
class StateAndMetaTest extends NodqoraIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void state_carries_every_node_key_in_the_environment() {
        JsonNode graph = http.getForObject("/api/environments/production/graph", JsonNode.class);
        JsonNode state = http.getForObject("/api/environments/production/state", JsonNode.class);

        // ADR-0057: absence is already load-bearing and already means something else — a key missing
        // from /graph means *deleted*. Two payloads polled at different rates, in which a missing key
        // means "deleted" in one and "fine, just unwatched" in the other, is a bug waiting for its
        // first incident.
        assertThat(state.get("nodes").findValuesAsText("nodeKey"))
                .containsExactlyElementsOf(graph.get("nodes").findValuesAsText("key"));
    }

    @Test
    void every_node_is_unknown_and_that_is_the_honest_answer() {
        JsonNode state = http.getForObject("/api/environments/production/state", JsonNode.class);

        // ADR-0011: `yaml` declares Discovery and not Health, so its nodes being permanently UNKNOWN
        // is a consequence of the capability set rather than a rule anyone wrote. ADR-0028 makes the
        // rendering arithmetic: no contributions, no row, and the join synthesizes all four fields.
        //
        // This is the same answer /state will synthesize in slice 3 for any node nothing observes,
        // so the frontend's health path does not change when contributions arrive — only its source.
        state.get("nodes").forEach(node -> {
            assertThat(node.get("health").asText()).isEqualTo("UNKNOWN");
            assertThat(node.get("rawSignal").isNull()).isTrue();
            assertThat(node.get("metrics")).isEmpty();
            assertThat(node.get("observedAt").isNull()).isTrue();
        });
    }

    @Test
    void state_has_no_health_observers_to_report_on_yet() {
        JsonNode state = http.getForObject("/api/environments/production/state", JsonNode.class);

        // ADR-0085: the roster is a *config* count, so this is empty because no configured plugin
        // declares the Health capability — not because nothing has reported.
        assertThat(state.get("plugins")).isEmpty();
        assertThat(state.get("observedAt").isNull()).isTrue();
    }

    @Test
    void meta_publishes_the_rosters_and_the_server_s_own_intervals() {
        JsonNode meta = http.getForObject("/api/meta", JsonNode.class);

        assertThat(meta.get("environments").findValuesAsText("key")).containsExactly("production", "staging");
        assertThat(meta.get("plugins").get(0).get("id").asText()).isEqualTo("yaml");
        // ADR-0010: present iff the bean implements the interface.
        assertThat(capabilities(meta)).containsExactly("DISCOVERY");
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

    private static List<String> capabilities(JsonNode meta) {
        List<String> capabilities = new java.util.ArrayList<>();
        meta.get("plugins").get(0).get("capabilities").forEach(value -> capabilities.add(value.asText()));
        return capabilities;
    }
}
