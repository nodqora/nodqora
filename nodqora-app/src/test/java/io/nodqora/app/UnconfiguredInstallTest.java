// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * again, which leaves the context bound to exactly the file the image carries.
 *
 * <p>The decision this pins is that the process <em>boots</em>. Refusing to start would be
 * defensible against ADR-0014 — bad configuration fails at startup rather than at first poll — but
 * that rule is about configuration that is <em>wrong</em>, and absent configuration is not wrong.
 * {@code YamlTopologyConfig} already reasons the same way one level down: directory existence is
 * deliberately not a startup constraint, because a mount that arrives late should not stop the
 * process. A typo in a compose volume path turning into a crash-loop is a worse first five minutes
 * than an empty roster, and Flyway still migrating means the fix is an edit and a restart.
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

    /** ADR-0055's roster, served empty rather than not served. */
    @Test
    void the_roster_is_empty_and_the_endpoint_still_answers() {
        JsonNode meta = http.getForObject("/api/meta", JsonNode.class);

        assertThat(meta.get("environments"))
                .as("an unconfigured install reports no environments; it does not invent one")
                .isEmpty();
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
}
