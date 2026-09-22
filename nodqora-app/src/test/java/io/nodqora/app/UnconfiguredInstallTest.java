// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * <b>A container with nothing mounted starts, and says so honestly.</b>
 *
 * <p>ADR-0152 ships no environments, so this is what a first {@code docker compose up} is before the
 * operator has written {@code /app/config/application.yaml}. Emptying
 * {@code spring.config.additional-location} takes the demo the {@code test} task loads back out
 * again, which leaves the context bound to exactly the file the image carries — and that file
 * declares no {@code nodqora.authentication}, so this is also <b>sign-in not configured</b>
 * (ADR-0173 §1, ADR-0178 §1).
 *
 * <p>The decision this pins is that the process <em>boots</em>, and serves nothing. Refusing to start
 * would be defensible against ADR-0014 — bad configuration fails at startup rather than at first
 * poll — but that rule is about configuration that is <em>wrong</em>, and absent configuration is
 * not wrong. A typo in a compose volume path turning into a crash-loop is a worse first five minutes
 * than a screen that says what to write, and Flyway still migrating means the fix is an edit and a
 * restart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.config.additional-location=")
class UnconfiguredInstallTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", NodqoraIntegrationTest.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", NodqoraIntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", NodqoraIntegrationTest.POSTGRES::getPassword);
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    /**
     * ADR-0173 §1: the read API is closed, {@code meta} included — it names every environment and
     * plugin, which is a description of what an organisation runs.
     */
    @Test
    void the_read_api_answers_401_in_problem_json() {
        ResponseEntity<String> meta = http.getForEntity("/api/meta", String.class);

        assertThat(meta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(meta.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(read(meta)).isEqualTo(Golden.read(json, "api-401.json"));
        assertThat(http.getForEntity("/api/environments/production/graph", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/api/environments/production/state", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** ADR-0177 §2: the shell learns why from here, and shows the first-run screen in place. */
    @Test
    void the_session_state_says_sign_in_is_not_configured() {
        ResponseEntity<String> session = http.getForEntity("/session", String.class);

        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(session.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(read(session)).isEqualTo(Golden.read(json, "session-not-configured.json"));
    }

    /**
     * ADR-0174 §4: everything outside {@code /api} is served, so the shell can load and render the
     * first-run screen. There is nowhere to redirect to.
     */
    @Test
    void the_shell_and_the_probe_are_served_so_the_first_run_screen_can_render() {
        assertThat(http.getForEntity("/", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/environments/production", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/healthz", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Nobody can sign in, so nothing the chain answers may write a session row — a probe polled every
     * few seconds would otherwise fill the table.
     */
    @Test
    void nothing_it_answers_writes_a_session() {
        jdbc.execute("delete from spring_session");
        http.getForEntity("/api/meta", String.class);
        http.getForEntity("/", String.class);
        http.getForEntity("/session", String.class);

        assertThat(jdbc.queryForObject("select count(*) from spring_session", Integer.class)).isZero();
    }

    /**
     * The reason booting is worth something. ADR-0150 bundles PostgreSQL and Flyway migrates itself,
     * so the operator who fixes their mount restarts into a schema that is already there.
     */
    @Test
    void the_schema_migrated_anyway() {
        assertThat(jdbc.queryForObject("select count(*) from environment", Integer.class))
                .isZero();
    }

    static JsonNode read(ObjectMapper json, ResponseEntity<String> response) {
        try {
            return json.readTree(response.getBody());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private JsonNode read(ResponseEntity<String> response) {
        return read(json, response);
    }
}
