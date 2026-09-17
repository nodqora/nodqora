// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.signin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;

/**
 * ADR-0176's grammar, written in an {@code application.yaml} and loaded by Spring Boot the way an
 * install loads its mounted file, so Spring's flattening of property sources and its placeholder
 * grammar are both in the path, as they are in {@code SecretReferencesInAConfigFileTest}.
 */
class SignInDeclaredInAConfigFileTest {

    @Configuration
    @Import(SignInWiring.class)
    static class Declared {}

    @TempDir
    Path dir;

    @Test
    void an_absent_key_is_sign_in_not_configured() throws IOException {
        assertThat(declared("""
                nodqora:
                  refresh:
                    health: 30s
                """)).isEqualTo(new SignIn.NotConfigured());
    }

    @Test
    void none_is_an_open_install() throws IOException {
        assertThat(declared("""
                nodqora:
                  authentication: none
                """)).isEqualTo(new SignIn.Open());
    }

    @Test
    void a_provider_with_only_its_required_keys_takes_every_default() throws IOException {
        assertThat(declared("""
                nodqora:
                  authentication:
                    oidc:
                      base-url: http://localhost:8080
                      issuer: http://192.168.0.222:8080/realms/nodqora
                      client-id: nodqora
                """)).isEqualTo(new SignIn.Provider(
                        new SignIn.Oidc(
                                URI.create("http://localhost:8080"),
                                URI.create("http://192.168.0.222:8080/realms/nodqora"),
                                "nodqora",
                                Optional.empty(),
                                List.of("openid", "profile"),
                                "name"),
                        new SignIn.Session(Duration.ofMinutes(30), Duration.ofHours(12))));
    }

    @Test
    void a_full_declaration_keeps_what_it_says_and_resolves_its_secret_through_the_one_resolver()
            throws IOException {
        Path secret = Files.writeString(dir.resolve("oidc"), "s3cr3t\n");
        SignIn.Provider provider = (SignIn.Provider) declared("""
                nodqora:
                  authentication:
                    oidc:
                      base-url: https://nodqora.example.com
                      issuer: https://idp.example.com/realms/acme
                      client-id: nodqora
                      client-secret: ${file:%s}
                      scopes: [profile, email]
                      name-claim: preferred_username
                    session:
                      idle: 15m
                      absolute: 8h
                """.formatted(secret));

        assertThat(provider.oidc().clientSecret()).contains("s3cr3t");
        assertThat(provider.oidc().scopes())
                .as("without openid the flow is not OIDC, so it is added rather than refused")
                .containsExactly("openid", "profile", "email");
        assertThat(provider.oidc().nameClaim()).isEqualTo("preferred_username");
        assertThat(provider.session())
                .isEqualTo(new SignIn.Session(Duration.ofMinutes(15), Duration.ofHours(8)));
    }

    @Test
    void the_escaped_form_is_a_reference_here_too() throws IOException {
        Path secret = Files.writeString(dir.resolve("oidc"), "s3cr3t");
        SignIn.Provider provider = (SignIn.Provider) declared("""
                nodqora:
                  authentication:
                    oidc:
                      base-url: http://localhost:8080
                      issuer: http://idp.local/realms/acme
                      client-id: nodqora
                      client-secret: '\\${file:%s}'
                """.formatted(secret));

        assertThat(provider.oidc().clientSecret()).contains("s3cr3t");
    }

    /** Environment variables arrive in their relaxed form, which the grammar check must still know. */
    @Test
    void a_provider_declared_entirely_in_the_environment_binds() throws IOException {
        SignIn.Provider provider = (SignIn.Provider) declared("""
                nodqora:
                  refresh:
                    health: 30s
                """, Map.of(
                        "NODQORA_AUTHENTICATION_OIDC_BASEURL", "http://localhost:8080",
                        "NODQORA_AUTHENTICATION_OIDC_ISSUER", "http://idp.local/realms/acme",
                        "NODQORA_AUTHENTICATION_OIDC_CLIENTID", "nodqora",
                        "NODQORA_AUTHENTICATION_OIDC_NAMECLAIM", "preferred_username",
                        "NODQORA_AUTHENTICATION_SESSION_ABSOLUTE", "1h"));

        assertThat(provider.oidc().clientId()).isEqualTo("nodqora");
        assertThat(provider.oidc().nameClaim()).isEqualTo("preferred_username");
        assertThat(provider.session().absolute()).isEqualTo(Duration.ofHours(1));
    }

    /**
     * ADR-0152 found that a later property source cannot delete a key an earlier one set, so {@code
     * NODQORA_AUTHENTICATION=none} in a compose file and an {@code oidc:} block in the mounted file
     * both arrive. Failing open and ignoring a declaration would both be guesses.
     */
    @Test
    void none_from_the_environment_beside_a_provider_in_the_file_refuses_to_start() {
        assertRefused("""
                nodqora:
                  authentication:
                    oidc:
                      base-url: http://localhost:8080
                      issuer: http://idp.local/realms/acme
                      client-id: nodqora
                """, Map.of("NODQORA_AUTHENTICATION", "none"),
                "nodqora.authentication is none, and nodqora.authentication.oidc is declared too");
    }

    @Test
    void none_in_the_file_beside_a_session_limit_from_the_environment_refuses_to_start() {
        assertRefused("""
                nodqora:
                  authentication: none
                """, Map.of("NODQORA_AUTHENTICATION_SESSION_IDLE", "10m"),
                "nodqora.authentication is none, and nodqora.authentication.session is declared too");
    }

    @ParameterizedTest
    @ValueSource(strings = {"base-url", "issuer", "client-id"})
    void a_provider_missing_a_required_key_refuses_to_start(String missing) {
        String complete = """
                base-url: http://localhost:8080
                issuer: http://idp.local/realms/acme
                client-id: nodqora
                """;
        String partial = complete.lines()
                .filter(line -> !line.startsWith(missing + ":"))
                .map(line -> "      " + line)
                .collect(Collectors.joining("\n"));
        assertRefused("""
                nodqora:
                  authentication:
                    oidc:
                %s
                """.formatted(partial), "nodqora.authentication.oidc.%s is required".formatted(missing));
    }

    @Test
    void session_limits_without_a_provider_refuse_to_start() {
        assertRefused("""
                nodqora:
                  authentication:
                    session:
                      idle: 10m
                """, "nodqora.authentication.session is declared without nodqora.authentication.oidc");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            base-url | localhost:8080              | which is not an absolute http or https URL
            base-url | ftp://nodqora.example.com   | which is not an absolute http or https URL
            base-url | https://example.com/nodqora | which has a path
            issuer   | idp.local/realms/acme       | which is not an absolute http or https URL
            """)
    void a_malformed_url_refuses_to_start(String key, String value, String why) {
        Map<String, String> oidc = new LinkedHashMap<>(Map.of(
                "base-url", "http://localhost:8080", "issuer", "http://idp.local/realms/acme"));
        oidc.put(key, value);
        assertRefused("""
                nodqora:
                  authentication:
                    oidc:
                      base-url: "%s"
                      issuer: "%s"
                      client-id: nodqora
                """.formatted(oidc.get("base-url"), oidc.get("issuer")),
                "nodqora.authentication.oidc.%s is '%s', %s".formatted(key, value, why));
    }

    @Test
    void a_base_url_ending_in_a_slash_has_no_path() throws IOException {
        SignIn.Provider provider = (SignIn.Provider) declared("""
                nodqora:
                  authentication:
                    oidc:
                      base-url: https://nodqora.example.com/
                      issuer: https://idp.example.com/realms/acme
                      client-id: nodqora
                """);

        assertThat(provider.oidc().baseUrl()).isEqualTo(URI.create("https://nodqora.example.com"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            2h  | 1h  | nodqora.authentication.session.idle (PT2H) is longer than nodqora.authentication.session.absolute (PT1H)
            0s  | 1h  | nodqora.authentication.session.idle is PT0S, which is not positive
            10m | -1h | nodqora.authentication.session.absolute is PT-1H, which is not positive
            """)
    void session_limits_the_idle_one_could_never_reach_refuse_to_start(String idle, String absolute, String reason) {
        assertRefused("""
                nodqora:
                  authentication:
                    oidc:
                      base-url: http://localhost:8080
                      issuer: http://idp.local/realms/acme
                      client-id: nodqora
                    session:
                      idle: %s
                      absolute: %s
                """.formatted(idle, absolute), reason);
    }

    @Test
    void a_session_limit_that_is_not_a_duration_refuses_to_start() {
        assertRefused("""
                nodqora:
                  authentication:
                    oidc:
                      base-url: http://localhost:8080
                      issuer: http://idp.local/realms/acme
                      client-id: nodqora
                    session:
                      idle: soon
                """, "nodqora.authentication.session.idle");
    }

    @Test
    void a_secret_reference_that_does_not_resolve_refuses_to_start() {
        assertRefused("""
                nodqora:
                  authentication:
                    oidc:
                      base-url: http://localhost:8080
                      issuer: http://idp.local/realms/acme
                      client-id: nodqora
                      client-secret: ${file:%s}
                """.formatted(dir.resolve("absent")),
                "nodqora.authentication.oidc.client-secret: config references ${file:%s}, which is unreadable"
                        .formatted(dir.resolve("absent")));
    }

    /**
     * {@code signin} supplies its own client registration, so Boot backs off and these keys would
     * bind to nothing. The operator is sent to the grammar Nodqora does read.
     */
    @Test
    void a_spring_oauth2_client_key_in_the_file_refuses_to_start_and_names_nodqoras_own() {
        assertRefused("""
                spring:
                  security:
                    oauth2:
                      client:
                        registration:
                          keycloak:
                            client-id: nodqora
                """, "spring.security.oauth2.client.registration.keycloak.client-id is not read by Nodqora: "
                        + "declare sign-in under nodqora.authentication.oidc");
    }

    @Test
    void a_spring_oauth2_client_key_from_the_environment_refuses_to_start() {
        assertRefused("""
                nodqora:
                  authentication: none
                """, Map.of("SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_ISSUERURI", "http://idp.local"),
                "is not read by Nodqora: declare sign-in under nodqora.authentication.oidc");
    }

    @Test
    void a_value_other_than_none_refuses_to_start() {
        assertRefused("""
                nodqora:
                  authentication: oidc
                """, "nodqora.authentication is 'oidc': it takes none, or an oidc block");
    }

    /** A misspelt key is there and wrong, not absent, so it does not boot to the first-run screen. */
    @Test
    void a_key_the_grammar_does_not_have_refuses_to_start() {
        assertRefused("""
                nodqora:
                  authentication:
                    odic:
                      base-url: http://localhost:8080
                """, "nodqora.authentication.odic.base-url is not a sign-in key");
    }

    private void assertRefused(String yaml, Map<String, Object> environment, String reason) {
        assertThatThrownBy(() -> start(yaml, environment).close()).hasStackTraceContaining(reason);
    }

    private void assertRefused(String yaml, String reason) {
        assertRefused(yaml, Map.of(), reason);
    }

    private SignIn declared(String yaml) throws IOException {
        return declared(yaml, Map.of());
    }

    /** {@code environment} stands in for the process environment, as a compose file would set it. */
    private SignIn declared(String yaml, Map<String, Object> environment) throws IOException {
        try (ConfigurableApplicationContext context = start(yaml, environment)) {
            return context.getBean(SignIn.class);
        }
    }

    private ConfigurableApplicationContext start(String yaml, Map<String, Object> environment)
            throws IOException {
        Path file = Files.writeString(dir.resolve("application.yaml"), yaml);
        return new SpringApplicationBuilder(Declared.class)
                .web(WebApplicationType.NONE)
                .environment(new StandardEnvironment() {
                    @Override
                    public Map<String, Object> getSystemEnvironment() {
                        return environment;
                    }
                })
                .run("--spring.config.location=file:" + file);
    }
}
