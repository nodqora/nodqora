// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nodqora.core.CoreConfiguration;
import io.nodqora.plugin.api.DiscoveryCapability;
import io.nodqora.plugin.api.DiscoveryRequest;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.Outcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * ADR-0014's references, written in an {@code application.yaml} and loaded by Spring Boot the way an
 * install loads its mounted file (#111).
 *
 * <p>{@link BoundConfigurationTest} hands the binder a {@code Map}, so a reference reaches it intact
 * whatever Spring would have done to it. Spring's own placeholder grammar is
 * {@code ${name:default}}, and it reads {@code ${file:/path}} as property {@code file} with the path
 * as its default — which only happens on this path, through a real file.
 */
class SecretReferencesInAConfigFileTest {

    record ProbeConfig(String kubeconfig, String path, String escaped) {}

    static final class ProbePlugin implements DiscoveryCapability<ProbeConfig> {

        @Override
        public String id() {
            return "probe";
        }

        @Override
        public String displayLabel() {
            return "Probe";
        }

        @Override
        public Class<ProbeConfig> configType() {
            return ProbeConfig.class;
        }

        @Override
        public DiscoveryResult discover(DiscoveryRequest<ProbeConfig> request) {
            return new DiscoveryResult(List.of(), List.of(), List.of(), List.of(), Outcome.complete());
        }
    }

    @Configuration
    @Import({CoreConfiguration.class, BoundConfiguration.class})
    static class Probed {

        @Bean
        ProbePlugin probe() {
            return new ProbePlugin();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }
    }

    @TempDir
    Path dir;

    @Test
    void file_and_env_references_reach_the_plugin_resolved_rather_than_expanded_by_spring() throws IOException {
        Path kubeconfig = Files.writeString(dir.resolve("kubeconfig"), "apiVersion: v1\nkind: Config\n");
        // The test process's own environment is the only one SecretReferences reads, and PATH is
        // set in every one of them.
        ProbeConfig config = boundFrom("""
                nodqora:
                  plugins:
                    registry-order: [probe]
                    precedence: [probe]
                  environments:
                    production:
                      plugins:
                        probe:
                          kubeconfig: "${file:%s}"
                          path: "${env:PATH}"
                """.formatted(kubeconfig));

        assertThat(config.kubeconfig()).isEqualTo("apiVersion: v1\nkind: Config");
        assertThat(config.path()).isEqualTo(System.getenv("PATH"));
    }

    @Test
    void the_escaped_form_the_interim_docs_prescribed_still_resolves() throws IOException {
        // Installs that followed the docs while #111 was open carry `'\${...}'`. Once Spring stops
        // expanding placeholders in a plugin slice the backslash survives into the value, and it
        // must not turn a working install into one that sends the reference text as a credential.
        Path kubeconfig = Files.writeString(dir.resolve("kubeconfig"), "apiVersion: v1\n");
        ProbeConfig config = boundFrom("""
                nodqora:
                  plugins:
                    registry-order: [probe]
                    precedence: [probe]
                  environments:
                    production:
                      plugins:
                        probe:
                          escaped: '\\${file:%s}'
                """.formatted(kubeconfig));

        assertThat(config.escaped()).isEqualTo("apiVersion: v1");
    }

    private ProbeConfig boundFrom(String yaml) throws IOException {
        Path file = Files.writeString(dir.resolve("application.yaml"), yaml);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Probed.class)
                .web(WebApplicationType.NONE)
                .run("--spring.config.location=file:" + file)) {
            return (ProbeConfig) context.getBean(BoundConfiguration.class)
                    .discoveryPairs("production")
                    .getFirst()
                    .config();
        }
    }
}
