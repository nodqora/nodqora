// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.signin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The grammar of ADR-0176 binds before anything acts on it. An operator who declares a provider
 * believes the install is closed, so until a chain closes it the declaration is refused — booted
 * the way {@code SignInDeclaredInAConfigFileTest} boots one, with the refusal beside the wiring as
 * the application's scan finds them.
 */
class ADeclaredProviderIsEnforcedOrRefusedTest {

    @Configuration
    @Import({SignInWiring.class, SignInIsNotEnforcedYet.class})
    static class Assembled {}

    @TempDir
    Path dir;

    @Test
    void a_declared_provider_refuses_to_start_while_nothing_enforces_it() {
        assertThatThrownBy(() -> start("""
                nodqora:
                  authentication:
                    oidc:
                      base-url: http://localhost:8080
                      issuer: http://idp.local/realms/acme
                      client-id: nodqora
                """))
                .hasStackTraceContaining("nodqora.authentication.oidc is declared, and this build does not sign anyone in yet")
                .hasStackTraceContaining("would be served to anyone who can reach it");
    }

    @Test
    void an_install_that_declares_no_provider_starts_as_it_always_has() throws IOException {
        try (ConfigurableApplicationContext absent = start("""
                nodqora:
                  refresh:
                    health: 30s
                """)) {
            assertThat(absent.getBean(SignIn.class)).isEqualTo(new SignIn.NotConfigured());
        }
        try (ConfigurableApplicationContext open = start("""
                nodqora:
                  authentication: none
                """)) {
            assertThat(open.getBean(SignIn.class)).isEqualTo(new SignIn.Open());
        }
    }

    private ConfigurableApplicationContext start(String yaml) throws IOException {
        Path file = Files.writeString(dir.resolve("application.yaml"), yaml);
        return new SpringApplicationBuilder(Assembled.class)
                .web(WebApplicationType.NONE)
                .run("--spring.config.location=file:" + file);
    }
}
