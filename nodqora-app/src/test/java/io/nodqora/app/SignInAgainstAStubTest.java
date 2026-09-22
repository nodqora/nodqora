// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Sign-in's behaviour against a provider in the test process (ADR-0178 §2).
 *
 * <p>{@code base-url} is an {@code https} host the test never reaches. The browser is this test, so
 * it can carry a callback addressed to that host back to the app on its random port — and every
 * {@code redirect_uri} and cookie flag asserted here is therefore visibly {@code base-url}'s, not
 * something read off the socket.
 *
 * <p>No environments are loaded: what is read after signing in is the roster, empty, which is the
 * read path answering at all. What it shows is the other tests' business (ADR-0178 §1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.config.additional-location=",
    "nodqora.authentication.oidc.base-url=" + SignInAgainstAStubTest.BASE_URL,
    "nodqora.authentication.oidc.client-id=" + StubProvider.CLIENT_ID,
    "nodqora.authentication.oidc.client-secret=" + StubProvider.CLIENT_SECRET,
    "nodqora.authentication.session.idle=17m",
    "nodqora.authentication.session.absolute=2h"
})
class SignInAgainstAStubTest {

    static final String BASE_URL = "https://nodqora.example.com";

    static final StubProvider PROVIDER = StubProvider.start();

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

    /** Core's {@link Clock}, moved by hand, so the absolute limit is proven without waiting (§4). */
    static final class MovableClock extends Clock {

        private volatile Instant now = Instant.now();

        void advance(Duration by) {
            now = now.plus(by);
        }

        void reset() {
            now = Instant.now();
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class Time {

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionRepository<? extends Session> sessions;

    @Autowired
    MovableClock clock;

    private final Browser browser = new Browser();

    // ADR-0173 §3's table, with a provider configured.

    @Test
    void the_read_api_answers_401_before_sign_in() {
        Browser.Response meta = browser.get(app("/api/meta"));

        assertThat(meta.status()).isEqualTo(401);
        assertThat(meta.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(read(meta)).isEqualTo(Golden.read(json, "api-401.json"));
        assertThat(browser.get(app("/api/environments/production/graph")).status()).isEqualTo(401);
        assertThat(browser.get(app("/api/environments/production/state")).status()).isEqualTo(401);
    }

    @Test
    void every_page_asks_the_provider_with_a_relative_redirect() {
        for (String page : List.of("/", "/index.html", "/environments/production?node=orders", "/environments")) {
            Browser.Response response = browser.get(app(page));

            assertThat(response.status()).as(page).isEqualTo(302);
            assertThat(response.rawLocation()).as(page).isEqualTo("/oauth2/authorization/oidc");
        }
    }

    @Test
    void assets_the_session_state_and_the_probe_are_served_without_sign_in() {
        assertThat(browser.get(app("/assets/index-a1b2c3.js")).status()).isEqualTo(200);
        // The icons are Vite output, gitignored and absent from a clean checkout, so a 404 is fine here:
        // what matters is that the chain lets the path through rather than sending it to sign in.
        for (String icon : List.of("/icon.png", "/mark.png", "/apple-touch-icon.png")) {
            assertThat(browser.get(app(icon)).status()).as(icon).isIn(200, 404);
        }

        Browser.Response probe = browser.get(app("/healthz"));
        assertThat(probe.status()).isEqualTo(200);
        assertThat(probe.body()).isEmpty();

        Browser.Response session = browser.get(app("/session"));
        assertThat(session.status()).isEqualTo(200);
        assertThat(session.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(read(session)).isEqualTo(Golden.read(json, "session-signed-out.json"));
    }

    @Test
    void an_unsigned_request_writes_no_session_row_for_a_poll() {
        jdbc.execute("delete from spring_session");
        browser.get(app("/api/meta"));
        browser.get(app("/session"));
        browser.get(app("/healthz"));

        assertThat(jdbc.queryForObject("select count(*) from spring_session", Integer.class)).isZero();
    }

    // ADR-0176 §1: the client.

    @Test
    void the_redirect_uri_is_base_url_and_never_a_forwarded_header() {
        Browser.Response toProvider = browser.get(app("/oauth2/authorization/oidc"), Map.of(
                "X-Forwarded-Host", "attacker.example",
                "X-Forwarded-Proto", "http",
                "X-Forwarded-Port", "80",
                "Forwarded", "host=attacker.example;proto=http"));

        assertThat(toProvider.status()).isEqualTo(302);
        assertThat(toProvider.location().toString()).startsWith(PROVIDER.issuer() + "/protocol/openid-connect/auth?");
        Map<String, String> request = StubProvider.form(toProvider.location().getRawQuery());
        assertThat(request)
                .containsEntry("redirect_uri", BASE_URL + "/login/oauth2/code/oidc")
                .containsEntry("client_id", StubProvider.CLIENT_ID)
                .containsEntry("scope", "openid profile")
                .containsEntry("code_challenge_method", "S256")
                .containsKey("code_challenge")
                .containsKey("nonce");
    }

    @Test
    void signing_in_lands_back_on_the_url_first_asked_for_and_reads_the_api() {
        PROVIDER.signsInAs(StubProvider.User.ada());

        Browser.Response landed = signIn("/environments/production?node=orders");

        assertThat(landed.status()).isEqualTo(302);
        assertThat(landed.rawLocation()).isEqualTo("/environments/production?node=orders");
        assertThat(browser.get(app("/api/meta")).status()).isEqualTo(200);
        assertThat(withoutTheToken(read(browser.get(app("/session")))))
                .isEqualTo(Golden.read(json, "session-signed-in.json"));
    }

    @Test
    void the_displayed_name_falls_back_from_the_name_claim_to_preferred_username_to_sub() {
        assertThat(nameOf(new StubProvider.User("s-1", Map.of("name", "Grace Hopper", "preferred_username", "grace"))))
                .isEqualTo("Grace Hopper");
        assertThat(nameOf(new StubProvider.User("s-2", Map.of("preferred_username", "nameless"))))
                .isEqualTo("nameless");
        assertThat(nameOf(new StubProvider.User("f81d4fae-7dec-11d0-a765-00a0c91e6bf6", Map.of())))
                .isEqualTo("f81d4fae-7dec-11d0-a765-00a0c91e6bf6");
        assertThat(nameOf(new StubProvider.User("s-3", Map.of("name", " ", "preferred_username", "blank"))))
                .isEqualTo("blank");
    }

    // ADR-0175: the session.

    @Test
    void the_browser_holds_one_opaque_cookie_secure_because_base_url_is_https() {
        Browser.Response start = browser.get(app("/environments/production"));
        Browser.Response toProvider = browser.get(start.location());
        URI callback = browser.get(toProvider.location()).location();
        Browser.Response landed = browser.get(app(callback.getRawPath() + "?" + callback.getRawQuery()));

        List<String> cookies = java.util.stream.Stream.of(start, toProvider, landed)
                .flatMap(response -> response.setCookies().stream())
                .toList();
        assertThat(cookies).isNotEmpty().allSatisfy(cookie -> assertThat(cookie)
                .startsWith("SESSION=")
                .contains("; Secure")
                .contains("; HttpOnly")
                .contains("; SameSite=Lax"));
    }

    @Test
    void the_session_holds_the_id_token_and_no_token_it_could_sign_in_with() {
        signIn("/");

        String rows = new String(jdbc.queryForList(
                        "select attribute_bytes from spring_session_attributes", byte[].class)
                .stream()
                .reduce(new byte[0], SignInAgainstAStubTest::concat), StandardCharsets.ISO_8859_1);
        assertThat(rows).contains(PROVIDER.lastIdToken());
        assertThat(rows).doesNotContain(PROVIDER.lastAccessToken()).doesNotContain("stub-refresh-");
    }

    @Test
    void the_idle_limit_is_session_idle() {
        signIn("/");

        Session session = sessions.findById(sessionId());

        assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(17));
    }

    @Test
    void the_absolute_limit_ends_a_session_however_busy_it_is() {
        clock.reset();
        signIn("/");
        try {
            clock.advance(Duration.ofHours(2).minusMinutes(1));
            assertThat(browser.get(app("/api/meta")).status()).isEqualTo(200);

            // Past the limit by more than the sign-in took, since the session began after the reset.
            clock.advance(Duration.ofMinutes(2));
            assertThat(browser.get(app("/api/meta")).status()).isEqualTo(401);
            assertThat(read(browser.get(app("/session")))).isEqualTo(Golden.read(json, "session-signed-out.json"));
        } finally {
            clock.reset();
        }
    }

    @Test
    void signing_out_ends_the_session_and_then_ends_it_at_the_provider() {
        signIn("/");
        String session = sessionId();
        JsonNode csrf = read(browser.get(app("/session"))).get("csrf");

        Browser.Response out = browser.post(app("/logout"),
                Map.of(csrf.get("parameterName").asText(), csrf.get("token").asText()));

        assertThat(out.status()).isEqualTo(302);
        assertThat(out.location().toString()).startsWith(PROVIDER.issuer() + "/protocol/openid-connect/logout?");
        assertThat(StubProvider.form(out.location().getRawQuery()))
                .containsEntry("id_token_hint", PROVIDER.lastIdToken())
                .containsEntry("post_logout_redirect_uri", BASE_URL + "/");
        assertThat(sessions.findById(session)).isNull();
        assertThat(browser.get(app("/api/meta")).status()).isEqualTo(401);
    }

    @Test
    void signing_out_without_the_csrf_token_is_refused() {
        signIn("/");

        assertThat(browser.post(app("/logout"), Map.of()).status()).isEqualTo(403);
        assertThat(browser.get(app("/api/meta")).status()).isEqualTo(200);
    }

    @Test
    void a_callback_that_does_not_check_out_is_a_page_and_not_another_trip_to_the_provider() {
        Browser.Response callback = browser.get(app("/login/oauth2/code/oidc?code=forged&state=forged"));

        assertThat(callback.status()).isEqualTo(401);
        assertThat(callback.rawLocation()).isNull();
        assertThat(callback.body()).contains("Signing in did not succeed");
    }

    /** ADR-0175 §7: an upgrade that changes Spring Security's serialized form signs everyone out, once. */
    @Test
    void a_session_row_that_cannot_be_read_is_no_session() {
        signIn("/");
        jdbc.update("update spring_session_attributes set attribute_bytes = ? where attribute_name = ?",
                "not a serialized context".getBytes(StandardCharsets.UTF_8), "SPRING_SECURITY_CONTEXT");

        assertThat(browser.get(app("/api/meta")).status()).isEqualTo(401);
        assertThat(read(browser.get(app("/session")))).isEqualTo(Golden.read(json, "session-signed-out.json"));
    }

    /** The whole flow, from a page to the provider and back, as a browser would walk it. */
    private Browser.Response signIn(String page) {
        Browser.Response toStart = browser.get(app(page));
        Browser.Response toProvider = browser.get(toStart.location());
        Browser.Response toCallback = browser.get(toProvider.location());
        URI callback = toCallback.location();
        assertThat(callback.toString()).startsWith(BASE_URL + "/login/oauth2/code/oidc?");
        return browser.get(app(callback.getRawPath() + "?" + callback.getRawQuery()));
    }

    private String nameOf(StubProvider.User user) {
        Browser fresh = new Browser();
        PROVIDER.signsInAs(user);
        try {
            Browser.Response toProvider = fresh.get(fresh.get(app("/")).location());
            URI callback = fresh.get(toProvider.location()).location();
            fresh.get(app(callback.getRawPath() + "?" + callback.getRawQuery()));
            return read(fresh.get(app("/session"))).get("name").asText();
        } finally {
            PROVIDER.signsInAs(StubProvider.User.ada());
        }
    }

    /** Spring Session's cookie is the session id, base64-encoded. */
    private String sessionId() {
        String cookie = browser.cookie(app("/"), "SESSION").orElseThrow();
        return new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
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

    /** The CSRF token is per session, so the golden document carries its position, not its value. */
    private static JsonNode withoutTheToken(JsonNode session) {
        ((ObjectNode) session.get("csrf")).set("token", TextNode.valueOf("<token>"));
        return session;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] both = java.util.Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, both, left.length, right.length);
        return both;
    }
}
