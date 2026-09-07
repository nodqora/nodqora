// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nodqora.plugin.api.DiscoveryCapability;
import io.nodqora.plugin.api.DiscoveryRequest;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.Outcome;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ADR-0014's startup guarantee: configuration is file-declared and bound at startup, "so bad
 * configuration fails at startup rather than at first poll".
 *
 * <p>Failing at first poll would mean a {@code FAILED} snapshot every five minutes forever, which
 * under ADR-0046 retains the last good graph and so looks like nothing wrong at all.
 */
class BoundConfigurationTest {

    /** A stand-in plugin. The core knows no plugin, so what it binds must work for any of them. */
    private record ProbeConfig(@NotBlank String endpoint, List<String> scopes) {

        private ProbeConfig {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    private static final class ProbePlugin implements DiscoveryCapability<ProbeConfig> {

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

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static BoundConfiguration bind(Map<String, Object> pluginSlice, SecretReferences secrets) {
        Map<String, Map<String, Object>> plugins = new LinkedHashMap<>();
        plugins.put("probe", pluginSlice);
        NodqoraProperties properties = new NodqoraProperties(
                new NodqoraProperties.Plugins(List.of("probe"), List.of("probe")),
                new NodqoraProperties.Refresh(Duration.ofMinutes(5), Duration.ofSeconds(30)),
                Map.of("production", new NodqoraProperties.Environment("Production", plugins)));

        BoundConfiguration configuration = new BoundConfiguration(
                properties, List.of(new ProbePlugin()), new ObjectMapper(), VALIDATOR, secrets);
        configuration.bind();
        return configuration;
    }

    private static SecretReferences noSecrets() {
        return new SecretReferences(name -> null);
    }

    @Test
    void binds_each_plugins_slice_into_its_declared_config_type() {
        BoundConfiguration configuration = bind(Map.of("endpoint", "https://probe.internal"), noSecrets());

        assertThat(configuration.discoveryPairs("production"))
                .singleElement()
                .satisfies(pair -> assertThat(pair.config()).isEqualTo(new ProbeConfig("https://probe.internal", List.of())));
    }

    @Test
    void a_violated_constraint_fails_the_process_rather_than_the_first_poll() {
        assertThatThrownBy(() -> bind(Map.of("endpoint", "  "), noSecrets()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production")
                .hasMessageContaining("probe")
                .hasMessageContaining("endpoint");
    }

    @Test
    void a_secret_reference_resolves_before_it_is_validated() {
        // A reference is not a value until it has been looked up, so a constraint applied first
        // would be checking the literal "${env:PROBE_ENDPOINT}".
        BoundConfiguration configuration = bind(
                Map.of("endpoint", "${env:PROBE_ENDPOINT}"),
                new SecretReferences(name -> "PROBE_ENDPOINT".equals(name) ? "https://resolved" : null));

        assertThat(configuration.discoveryPairs("production").getFirst().config())
                .isEqualTo(new ProbeConfig("https://resolved", List.of()));
    }

    @Test
    void an_unset_secret_reference_is_named_rather_than_silently_empty() {
        assertThatThrownBy(() -> bind(Map.of("endpoint", "${env:PROBE_ENDPOINT}"), noSecrets()))
                .hasMessageContaining("PROBE_ENDPOINT");
    }

    @Test
    void a_list_valued_key_survives_the_relaxed_binder() {
        // Spring binds a plugin's slice as `Map<String, Object>` — the core cannot bind into a shape
        // it does not know (ADR-0014) — and a YAML list nested in that arrives keyed by index.
        // Left alone, every list-valued key any plugin declares fails at startup with a Jackson
        // message about `ArrayList` that says nothing about the file the operator wrote.
        Map<String, Object> slice = new LinkedHashMap<>();
        slice.put("endpoint", "https://probe.internal");
        slice.put("scopes", Map.of("1", "second", "0", "first"));

        assertThat(bind(slice, noSecrets()).discoveryPairs("production").getFirst().config())
                .isEqualTo(new ProbeConfig("https://probe.internal", List.of("first", "second")));
    }

    @Test
    void an_order_that_does_not_name_every_registered_plugin_fails() {
        NodqoraProperties properties = new NodqoraProperties(
                new NodqoraProperties.Plugins(List.of("probe"), List.of("prboe")),
                null,
                Map.of());

        // A misspelled id is otherwise silent: it sorts last, and the graph goes on being folded
        // with the wrong precedence.
        assertThatThrownBy(() -> new BoundConfiguration(
                                properties, List.of(new ProbePlugin()), new ObjectMapper(), VALIDATOR, noSecrets())
                        .bind())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nodqora.plugins.precedence");
    }

    @Test
    void a_plugin_configured_for_an_environment_but_not_registered_fails() {
        NodqoraProperties properties = new NodqoraProperties(
                new NodqoraProperties.Plugins(List.of("probe"), List.of("probe")),
                null,
                Map.of("production", new NodqoraProperties.Environment("Production", Map.of("absent", Map.of()))));

        assertThatThrownBy(() -> new BoundConfiguration(
                                properties, List.of(new ProbePlugin()), new ObjectMapper(), VALIDATOR, noSecrets())
                        .bind())
                .hasMessageContaining("unknown plugin 'absent'");
    }
}
