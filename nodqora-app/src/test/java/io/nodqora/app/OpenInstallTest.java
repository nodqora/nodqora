// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The image as shipped, with {@code nodqora.authentication: none} and still no environments
 * (ADR-0173 §2). {@link UnconfiguredInstallTest}'s empty roster moved here when that install became
 * closed (ADR-0178 §1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"spring.config.additional-location=", "nodqora.authentication=none"})
class OpenInstallTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", NodqoraIntegrationTest.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", NodqoraIntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", NodqoraIntegrationTest.POSTGRES::getPassword);
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    /** ADR-0055's roster, served empty rather than not served. */
    @Test
    void the_roster_is_empty_and_the_endpoint_still_answers() {
        JsonNode meta = http.getForObject("/api/meta", JsonNode.class);

        assertThat(meta.get("environments"))
                .as("an unconfigured install reports no environments; it does not invent one")
                .isEmpty();
    }

    /** ADR-0177 §2: the shell labels the install "Open — no sign-in", and has no sign-out to offer. */
    @Test
    void the_session_state_says_open() {
        assertThat(UnconfiguredInstallTest.read(json, http.getForEntity("/session", String.class)))
                .isEqualTo(Golden.read(json, "session-open.json"));
    }
}
