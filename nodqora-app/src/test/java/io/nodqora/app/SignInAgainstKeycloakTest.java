// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.util.HtmlUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * The flow once against the provider the demo and the docs tell people to run (ADR-0178 §3).
 *
 * <p>It imports {@code demo/keycloak/nodqora-realm.json} and changes one thing: the realm's origin.
 * The client's redirect and post-logout URIs name {@code http://localhost:8080}, and Keycloak matches
 * the port exactly, so this test chooses a free port first, starts Keycloak with that origin written
 * into the realm in place of {@code 8080}, then starts Nodqora on it. Users, claims, the client secret
 * and the SSO limit are proven as the demo has them — and so is the narrowed redirect URI, which is
 * {@code /login/oauth2/code/oidc} and nothing else.
 *
 * <p>Keycloak's login form is part of this test. An upgrade that changes the form breaks it, which is
 * when the demo's pinned version should be looked at anyway.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestPropertySource(properties = {
    "spring.config.additional-location=",
    "nodqora.authentication.oidc.client-id=nodqora",
    "nodqora.authentication.oidc.client-secret=nodqora-demo-client-secret"
})
class SignInAgainstKeycloakTest {

    /** The version the demo cluster runs (demo/k8s/70-keycloak.yaml). */
    private static final String KEYCLOAK = "quay.io/keycloak/keycloak:26.7.4";

    private static final Pattern LOGIN_FORM = Pattern.compile("<form[^>]*\\bid=\"kc-form-login\"[^>]*>");
    private static final Pattern ACTION = Pattern.compile("\\baction=\"([^\"]+)\"");

    static final int PORT = unusedPort();
    static final String ORIGIN = "http://localhost:" + PORT;

    static final GenericContainer<?> PROVIDER = new GenericContainer<>(KEYCLOAK)
            .withCommand("start-dev", "--import-realm")
            .withCopyToContainer(Transferable.of(realm()), "/opt/keycloak/data/import/nodqora.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/nodqora/.well-known/openid-configuration")
                    .forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    static {
        PROVIDER.start();
    }

    @DynamicPropertySource
    static void datasourceAndProvider(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", NodqoraIntegrationTest.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", NodqoraIntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", NodqoraIntegrationTest.POSTGRES::getPassword);
        registry.add("server.port", () -> PORT);
        registry.add("nodqora.authentication.oidc.base-url", () -> ORIGIN);
        registry.add("nodqora.authentication.oidc.issuer", SignInAgainstKeycloakTest::issuer);
    }

    @Autowired
    ObjectMapper json;

    @Test
    void ada_signs_in_through_the_login_form_and_is_shown_by_her_full_name() {
        Browser browser = new Browser();

        signIn(browser, "ada");

        assertThat(browser.get(app("/api/meta")).status()).isEqualTo(200);
        assertThat(session(browser).get("name").asText()).isEqualTo("Ada Lovelace");
    }

    @Test
    void a_user_with_no_name_claim_is_shown_by_username() {
        Browser browser = new Browser();

        signIn(browser, "nameless");

        assertThat(session(browser).get("name").asText()).isEqualTo("nameless");
    }

    @Test
    void signing_out_ends_the_session_at_keycloak_and_the_next_visit_asks_for_a_password() {
        Browser browser = new Browser();
        signIn(browser, "grace");
        JsonNode csrf = session(browser).get("csrf");

        Browser.Response out = browser.post(app("/logout"),
                Map.of(csrf.get("parameterName").asText(), csrf.get("token").asText()));
        assertThat(out.location().toString()).startsWith(issuer() + "/protocol/openid-connect/logout?");

        Browser.Response back = browser.get(out.location());
        assertThat(back.status()).isEqualTo(302);
        assertThat(back.location()).isEqualTo(app("/"));

        assertThat(browser.get(app("/api/meta")).status()).isEqualTo(401);
        Browser.Response again = browser.get(browser.get(browser.get(app("/")).location()).location());
        assertThat(again.status()).as("Keycloak shows its login form, not a silent sign-in").isEqualTo(200);
        assertThat(LOGIN_FORM.matcher(again.body()).find()).isTrue();
    }

    /** A page, Nodqora's redirect, Keycloak's form, the callback, and back where it started. */
    private void signIn(Browser browser, String user) {
        Browser.Response toStart = browser.get(app("/environments/production?node=orders"));
        Browser.Response toProvider = browser.get(toStart.location());
        Browser.Response form = browser.get(toProvider.location());
        assertThat(form.status()).isEqualTo(200);

        Browser.Response toCallback = browser.post(action(form), Map.of(
                "username", user, "password", user, "credentialId", ""));
        assertThat(toCallback.status()).as("Keycloak accepted " + user).isEqualTo(302);
        assertThat(toCallback.location().toString()).startsWith(ORIGIN + "/login/oauth2/code/oidc?");

        Browser.Response landed = browser.get(toCallback.location());
        assertThat(landed.status()).isEqualTo(302);
        assertThat(landed.rawLocation()).isEqualTo("/environments/production?node=orders");
    }

    private static URI action(Browser.Response form) {
        Matcher tag = LOGIN_FORM.matcher(form.body());
        assertThat(tag.find()).as("Keycloak's login form").isTrue();
        Matcher action = ACTION.matcher(tag.group());
        assertThat(action.find()).isTrue();
        return form.uri().resolve(HtmlUtils.htmlUnescape(action.group(1)));
    }

    private JsonNode session(Browser browser) {
        try {
            return json.readTree(browser.get(app("/session")).body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static URI app(String path) {
        return URI.create(ORIGIN + path);
    }

    private static String issuer() {
        return "http://" + PROVIDER.getHost() + ":" + PROVIDER.getMappedPort(8080) + "/realms/nodqora";
    }

    /** The demo realm with its origin moved to this test's port, and nothing else changed. */
    private static String realm() {
        try {
            return Files.readString(Path.of("demo", "keycloak", "nodqora-realm.json"))
                    .replace("http://localhost:8080", ORIGIN);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
