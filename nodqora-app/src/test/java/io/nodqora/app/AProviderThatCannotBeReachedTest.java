// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * ADR-0176 §4: an unreachable provider is an outage, not a misconfiguration (ADR-0178 §2).
 *
 * <p>Discovery is kept once it succeeds, so these run in order in one context whose provider starts
 * down, and the one that brings it back runs last. A context of its own proves that a port nothing
 * listens on is the same outage as a provider that answers with an error.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.config.additional-location=",
    "nodqora.authentication.oidc.base-url=http://localhost:8080",
    "nodqora.authentication.oidc.client-id=" + StubProvider.CLIENT_ID,
    "nodqora.authentication.oidc.client-secret=" + StubProvider.CLIENT_SECRET
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class AProviderThatCannotBeReachedTest {

    static final StubProvider PROVIDER = StubProvider.start();

    static {
        PROVIDER.goesDown();
    }

    @DynamicPropertySource
    static void datasourceAndProvider(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", NodqoraIntegrationTest.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", NodqoraIntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", NodqoraIntegrationTest.POSTGRES::getPassword);
        registry.add("nodqora.authentication.oidc.issuer", () -> PROVIDER.issuer().toString());
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    SessionRepository<? extends Session> sessions;

    private final Browser browser = new Browser();

    @Test
    @Order(1)
    void nodqora_boots_and_a_browser_that_must_sign_in_is_told_which_provider_is_down(CapturedOutput log) {
        Browser.Response toStart = browser.get(app("/environments/production"));
        assertThat(toStart.rawLocation()).isEqualTo("/oauth2/authorization/oidc");

        Browser.Response page = browser.get(toStart.location());

        assertThat(page.status()).isEqualTo(503);
        assertThat(page.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/html"));
        assertThat(page.body()).contains(PROVIDER.issuer().toString());
        assertThat(log.getOut()).contains("ERROR").contains("Sign-in cannot reach the identity provider at " + PROVIDER.issuer());
    }

    @Test
    @Order(2)
    void the_api_still_answers_401_and_the_probe_still_answers_200() {
        assertThat(browser.get(app("/api/meta")).status()).isEqualTo(401);
        assertThat(browser.get(app("/healthz")).status()).isEqualTo(200);
        assertThat(read(browser.get(app("/session")))).isEqualTo(Golden.read(json, "session-signed-out.json"));
    }

    /** An issuer typo announces itself at the first sign-in, exactly as an outage does. */
    @Test
    @Order(3)
    void a_document_naming_another_issuer_is_the_same_outage(CapturedOutput log) {
        PROVIDER.advertisesIssuer("http://localhost/realms/someone-else");
        try {
            Browser.Response page = browser.get(app("/oauth2/authorization/oidc"));

            assertThat(page.status()).isEqualTo(503);
            assertThat(page.body()).contains(PROVIDER.issuer().toString());
            assertThat(log.getOut()).contains("Sign-in cannot reach the identity provider at " + PROVIDER.issuer());
        } finally {
            PROVIDER.goesDown();
        }
    }

    /**
     * A session signed in by another replica, whose discovery succeeded where this one's has not. It
     * needs nothing from the provider to read, and signing it out here is local, because whether the
     * provider advertises {@code end_session_endpoint} cannot be known (ADR-0176 §4).
     */
    @Test
    @Order(4)
    void a_signed_in_session_keeps_reading_and_signs_out_locally() {
        browser.setCookie(app("/"), "SESSION", signedInOnAnotherReplica());

        assertThat(browser.get(app("/api/meta")).status()).isEqualTo(200);
        JsonNode session = read(browser.get(app("/session")));
        assertThat(session.get("name").asText()).isEqualTo("Ada Lovelace");

        Browser.Response out = browser.post(app("/logout"), Map.of(
                session.get("csrf").get("parameterName").asText(), session.get("csrf").get("token").asText()));

        assertThat(out.status()).isEqualTo(302);
        assertThat(out.rawLocation()).isEqualTo("/");
        assertThat(browser.get(app("/api/meta")).status()).isEqualTo(401);
    }

    /** Last, because it is the one that leaves discovery kept. */
    @Test
    @Order(5)
    void a_failed_discovery_is_not_kept_and_the_next_sign_in_succeeds_once_the_provider_is_back() {
        assertThat(browser.get(app("/oauth2/authorization/oidc")).status()).isEqualTo(503);

        PROVIDER.comesBack();
        Browser.Response toProvider = browser.get(app("/oauth2/authorization/oidc"));
        assertThat(toProvider.status()).isEqualTo(302);
        URI callback = browser.get(toProvider.location()).location();
        browser.get(app(callback.getRawPath() + "?" + callback.getRawQuery()));

        assertThat(browser.get(app("/api/meta")).status()).isEqualTo(200);
    }

    private String signedInOnAnotherReplica() {
        OidcIdToken idToken = OidcIdToken.withTokenValue("an-id-token-from-another-replica")
                .issuer(PROVIDER.issuer().toString())
                .subject("a0000000-0000-4000-8000-00000000000a")
                .audience(List.of(StubProvider.CLIENT_ID))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("name", "Ada Lovelace")
                .build();
        var authorities = AuthorityUtils.createAuthorityList("OIDC_USER");
        var signedIn = new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, idToken), authorities, "oidc");

        String id = saved(sessions, new SecurityContextImpl(signedIn));
        return Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }

    /** Through Spring Session's own repository, as the replica that signed it in would have written it. */
    private static <S extends Session> String saved(SessionRepository<S> sessions, SecurityContextImpl context) {
        S session = sessions.createSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        sessions.save(session);
        return session.getId();
    }

    private URI app(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private JsonNode read(Browser.Response response) {
        try {
            return json.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError(response.status() + " " + response.body(), e);
        }
    }

    /** Nothing listens on the issuer's port: the connection is refused, and the answer is the same. */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties = {
        "spring.config.additional-location=",
        "nodqora.authentication.oidc.base-url=http://localhost:8080",
        "nodqora.authentication.oidc.client-id=" + StubProvider.CLIENT_ID
    })
    @ExtendWith(OutputCaptureExtension.class)
    static class NothingListens {

        static final String ISSUER = "http://localhost:" + unusedPort() + "/realms/nowhere";

        @DynamicPropertySource
        static void datasourceAndProvider(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", NodqoraIntegrationTest.POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", NodqoraIntegrationTest.POSTGRES::getUsername);
            registry.add("spring.datasource.password", NodqoraIntegrationTest.POSTGRES::getPassword);
            registry.add("nodqora.authentication.oidc.issuer", () -> ISSUER);
        }

        @LocalServerPort
        int port;

        @Test
        void a_refused_connection_is_a_503_naming_the_issuer_and_an_error(CapturedOutput log) {
            Browser.Response page = new Browser().get(URI.create("http://localhost:" + port + "/oauth2/authorization/oidc"));

            assertThat(page.status()).isEqualTo(503);
            assertThat(page.body()).contains(ISSUER);
            assertThat(log.getOut()).contains("Sign-in cannot reach the identity provider at " + ISSUER);
        }

        private static int unusedPort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
