// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;

/**
 * Per-environment plugin configuration, declared in a file and bound at startup (ADR-0014). The
 * database holds only the environment roster; a write API for integration configuration on an
 * unauthenticated service is a credential-entry form open to anyone who reaches the ingress.
 *
 * <p>{@code environments} preserves declaration order, which is the tiebreak ADR-0077 uses for a
 * contested type descriptor after plugin precedence.
 */
public record NodqoraProperties(Plugins plugins, Refresh refresh, Map<String, Environment> environments) {

    public NodqoraProperties {
        plugins = plugins == null ? new Plugins(List.of(), List.of()) : plugins;
        refresh = refresh == null ? new Refresh(null, null) : refresh;
        environments = environments == null ? Map.of() : new LinkedHashMap<>(environments);
    }

    /**
     * Binds the {@code nodqora} tree, with {@code environments} bound <em>literally</em> (ADR-0165).
     *
     * <p>This is not {@code @ConfigurationProperties} because that binds with Spring's placeholder
     * resolution, whose grammar is {@code ${name:default}}: it reads {@code ${file:/path}} as
     * property {@code file} with the path as its default, and {@code ${env:VAR}} as the text
     * {@code VAR}, so {@link SecretReferences} never saw a reference at all (#111). A binder with no
     * placeholder resolver hands every reference over intact and leaves ADR-0014's resolver the only
     * one. The orders and cadences keep Spring's resolution; they hold no secrets.
     */
    public static NodqoraProperties from(org.springframework.core.env.Environment spring) {
        Binder resolving = Binder.get(spring);
        Binder literal = new Binder(ConfigurationPropertySources.get(spring));
        return new NodqoraProperties(
                resolving.bind("nodqora.plugins", Plugins.class).orElse(null),
                resolving.bind("nodqora.refresh", Refresh.class).orElse(null),
                literal.bind("nodqora.environments", Bindable.mapOf(String.class, Environment.class))
                        .orElse(null));
    }

    /**
     * The two orders over plugin ids (ADR-0011, ADR-0044, ADR-0050). They are configuration rather
     * than constants because ADR-0011 says precedence is "expressed as config over plugin ids", and
     * because ADR-0015 forbids the core naming a plugin in its own source at all.
     *
     * <p>Neither list carries a Bean Validation constraint, because
     * {@code BoundConfiguration.requireOrdersCoverEveryPlugin} already checks something strictly
     * stronger than non-emptiness — that each names <em>exactly</em> the registered plugins — and
     * says which ids are missing when it fails.
     */
    public record Plugins(List<String> registryOrder, List<String> precedence) {}

    /**
     * ADR-0035, ADR-0042: 5 minute discovery, 30 second health, both file-declared and both global
     * (ADR-0103). The timeout is derived as half the interval rather than configured, so it cannot
     * be misconfigured above it.
     */
    public record Refresh(Duration discovery, Duration health) {

        public Refresh {
            discovery = discovery == null ? Duration.ofMinutes(5) : discovery;
            health = health == null ? Duration.ofSeconds(30) : health;
        }
    }

    /**
     * One environment. {@code plugins} is keyed by plugin id and holds that plugin's own config
     * slice, bound into its declared {@code configType()} — so bad configuration fails at startup
     * rather than at first poll.
     */
    public record Environment(String displayName, Map<String, Map<String, Object>> plugins) {

        public Environment {
            plugins = plugins == null ? Map.of() : new LinkedHashMap<>(plugins);
        }
    }
}
